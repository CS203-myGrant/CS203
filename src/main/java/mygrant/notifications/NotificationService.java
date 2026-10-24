package mygrant.notifications;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.HashMap;


import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import mygrant.notifications.dto.NotificationResponse;
import mygrant.policies.PolicyDocumentRepository;
import mygrant.policies.PolicyIndexedEvent;
import mygrant.policies.SourceType;
import mygrant.testclock.TestClock;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;
import mygrant.user.UserProfile;
import mygrant.user.UserProfileRepository;

/** Creates policy alerts for matching applicants and serves each user's inbox. */
@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final PolicyImpactRules impactRules;
    private final UserProfileRepository userProfileRepository;
    private final NotificationStreamRegistry streamRegistry;
    private final PolicyDocumentRepository policyRepository;
    private final TestClock testClock;

    public NotificationService(NotificationRepository notificationRepository,
            UserRepository userRepository, UserProfileRepository userProfileRepository,
            PolicyImpactRules impactRules, NotificationStreamRegistry streamRegistry,
            PolicyDocumentRepository policyRepository, TestClock testClock) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.impactRules = impactRules;
        this.streamRegistry = streamRegistry;
        this.policyRepository = policyRepository;
        this.testClock = testClock;
    }

    /**
     * Notifies every applicant whose visa type the policy affects, flagging those who
     * must review profile information the policy touches. Runs after the
     * ingestion commits, in its own transaction, so a failure here never undoes an import.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPolicyIndexed(PolicyIndexedEvent event) {
        List<String> visaTypes = event.visaTypes().stream()
                .map(PolicyImpactRules::normalizeVisaType)
                .distinct()
                .toList();
        // A reader whose test clock is set before the publication date is alerted once their
        // clock reaches it, by syncToTestClock.
        List<User> affected = userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, visaTypes).stream()
                .filter(user -> testClock.isPublished(event.publicationDate(), user.getId()))
                .toList();
        String message = buildMessage(event);
        // The rules are visa-specific, so a policy covering several categories affects
        // different fields per reader. Read the policy text once for all of them.
        Map<String, Set<ProfileField>> fieldsByVisaType =
                impactRules.affectedFieldsByVisaType(visaTypes, event.title(), event.content());

        // Impact checks read the versioned profile, which holds fields such as the employer
        // that the legacy user row lacks. A user who never saved one falls back to their
        // registration details at version 1.
        Map<Long, UserProfile> profilesByUserId = new HashMap<>();
        userProfileRepository.findAllById(affected.stream().map(User::getId).toList())
                .forEach(profile -> profilesByUserId.put(profile.getUserId(), profile));

        List<Notification> saved = notificationRepository.saveAll(affected.stream()
                .map(user -> toNotification(
                        user,
                        profilesByUserId.getOrDefault(user.getId(), UserProfile.fromUser(user)),
                        event,
                        message,
                        fieldsByVisaType))
                .toList());
        publishAfterCommit(saved);
    }

    /**
     * TEMPORARY, for the test clock: makes the user's inbox match the date their clock
     * shows. Alerts for policies published after that date are removed, and every policy
     * published by then for the user's current visa category that they were never alerted
     * about is alerted now, oldest first so the newest tops the inbox.
     *
     * @return how many alerts were added and removed
     */
    @Transactional
    public InboxSync syncToTestClock(User user) {
        LocalDate today = testClock.today(user.getId());
        int removed = notificationRepository.deleteForPoliciesPublishedAfter(user.getId(), today);
        String visaType = PolicyImpactRules.normalizeVisaType(user.getVisaType());
        if (user.getRole() != UserRole.APPLICANT || visaType.isEmpty()) {
            return new InboxSync(0, removed);
        }

        UserProfile profile = userProfileRepository.findById(user.getId())
                .orElseGet(() -> UserProfile.fromUser(user));
        Set<Long> alreadyNotified = notificationRepository.findPolicyIdsByUserId(user.getId());
        // Only official policies raise alerts, as at ingestion.
        List<Notification> missing = policyRepository
                .findBySourceTypeAndPublicationDateLessThanEqualOrderByPublicationDateAscIdAsc(SourceType.PRIMARY, today)
                .stream()
                .filter(policy -> !alreadyNotified.contains(policy.getId()))
                .map(PolicyIndexedEvent::from)
                .filter(event -> event.visaTypes().stream()
                        .map(PolicyImpactRules::normalizeVisaType)
                        .anyMatch(visaType::equals))
                .map(event -> toNotification(user, profile, event, buildMessage(event),
                        impactRules.affectedFieldsByVisaType(event.visaTypes(), event.title(), event.content())))
                .toList();
        List<Notification> saved = notificationRepository.saveAll(missing);
        publishAfterCommit(saved);
        return new InboxSync(saved.size(), removed);
    }

    /** Alerts added and removed when an inbox was matched to a test clock date. */
    public record InboxSync(int added, int removed) {
    }

    // Publish only after these rows commit. A disconnected stream cannot undo persistence.
    private void publishAfterCommit(List<Notification> saved) {
        Runnable publishSaved = () -> {
            for (Notification notification : saved) {
                try {
                    streamRegistry.publish(notification.getUserId(), NotificationResponse.from(notification));
                } catch (RuntimeException ignored) {
                    // A disconnected or unhealthy stream never rolls back alert creation.
                }
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishSaved.run();
        } else TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishSaved.run();
            }
        });
    }

    private Notification toNotification(
        User user,
        UserProfile profile,
        PolicyIndexedEvent event,
        String message,
        Map<String, Set<ProfileField>> fieldsByVisaType) {
        Set<ProfileField> policyFields = fieldsByVisaType
                .getOrDefault(PolicyImpactRules.normalizeVisaType(user.getVisaType()), Set.of());
        List<ProfileField> toReview = policyFields.stream().filter(field -> field.isFilledIn(profile)).toList();
        if (toReview.isEmpty()) {
            return new Notification(
                    user.getId(),
                    profile.getVersion(),
                    event.policyId(),
                    message,
                    toReview);
        }
        String labels = toReview.stream().map(ProfileField::label).collect(Collectors.joining(", "));
        return new Notification(
                user.getId(),
                profile.getVersion(),
                event.policyId(),
                truncate(message + ". Action needed: review your " + labels + "."),
                toReview
        );
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> list(String email, Pageable pageable) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId(email), pageable)
                .map(NotificationResponse::from);
    }

    @Transactional(readOnly = true)
    public long unreadCount(String email) {
        return notificationRepository.countByUserIdAndReadAtIsNull(userId(email));
    }

    @Transactional(readOnly = true)
    public NotificationResponse get(String email, Long notificationId) {
        return notificationRepository.findByIdAndUserId(notificationId, userId(email))
                .map(NotificationResponse::from)
                .orElseThrow(() -> new NotificationNotFoundException(notificationId));
    }

    @Transactional
    public NotificationResponse markRead(String email, Long notificationId) {
        Notification notification = notificationRepository.findByIdAndUserId(notificationId, userId(email))
                .orElseThrow(() -> new NotificationNotFoundException(notificationId));
        notification.markRead(Instant.now());
        return NotificationResponse.from(notification);
    }

    private Long userId(String email) {
        return userRepository.findByEmail(email).map(User::getId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists"));
    }

    private String buildMessage(PolicyIndexedEvent event) {
        String message = "New policy affecting " + String.join(", ", event.visaTypes()) + ": " + event.title();
        if (event.effectiveDate() != null) {
            message += " (effective " + event.effectiveDate() + ")";
        }
        return truncate(message);
    }

    private String truncate(String message) {
        return message.length() > 1500 ? message.substring(0, 1497) + "..." : message;
    }
}
