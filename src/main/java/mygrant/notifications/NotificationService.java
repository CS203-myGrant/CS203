package mygrant.notifications;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.HashMap;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import mygrant.policies.PolicyRecommendationStore;
import mygrant.rules.DateNotice;
import mygrant.rules.PolicyChangedEvent;
import mygrant.rules.PolicyRuleStore;
import mygrant.rules.RuleChange;
import mygrant.testclock.TestClock;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;
import mygrant.user.UserProfile;
import mygrant.user.UserProfileRepository;
import mygrant.user.ProfileFieldConsentRepository;
import mygrant.user.ProfileFieldDefinition;
import mygrant.user.ImmigrationDocumentRepository;
import mygrant.user.ImmigrationDocumentType;
import org.springframework.beans.factory.annotation.Autowired;

/** Creates policy alerts for matching applicants and serves each user's inbox. */
@Service
public class NotificationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final PolicyImpactRules impactRules;
    private final UserProfileRepository userProfileRepository;
    private final NotificationStreamRegistry streamRegistry;
    private final PolicyDocumentRepository policyRepository;
    private final TestClock testClock;
    private final PolicyRuleStore ruleStore;
    private final ProfileFieldConsentRepository consentRepository;
    private final ImmigrationDocumentRepository immigrationDocumentRepository;
    private final PolicyRecommendationStore recommendationStore;

    @Autowired
    public NotificationService(NotificationRepository notificationRepository,
            UserRepository userRepository, UserProfileRepository userProfileRepository,
            PolicyImpactRules impactRules, NotificationStreamRegistry streamRegistry,
            PolicyDocumentRepository policyRepository, TestClock testClock, PolicyRuleStore ruleStore,
            ProfileFieldConsentRepository consentRepository,
            ImmigrationDocumentRepository immigrationDocumentRepository,
            PolicyRecommendationStore recommendationStore) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.impactRules = impactRules;
        this.streamRegistry = streamRegistry;
        this.policyRepository = policyRepository;
        this.testClock = testClock;
        this.ruleStore = ruleStore;
        this.consentRepository = consentRepository;
        this.immigrationDocumentRepository = immigrationDocumentRepository;
        this.recommendationStore = recommendationStore;
    }

    /** Without the consent and document lookups, so entry restrictions alert no one. */
    public NotificationService(NotificationRepository notificationRepository,
            UserRepository userRepository, UserProfileRepository userProfileRepository,
            PolicyImpactRules impactRules, NotificationStreamRegistry streamRegistry,
            PolicyDocumentRepository policyRepository, TestClock testClock, PolicyRuleStore ruleStore) {
        this(notificationRepository, userRepository, userProfileRepository, impactRules, streamRegistry,
                policyRepository, testClock, ruleStore, null, null, null);
    }

    /**
     * Notifies every applicant whose visa type the policy affects, flagging those who
     * must review profile information the policy touches. Runs after the
     * ingestion commits, in its own transaction, so a failure here never undoes an import.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPolicyIndexed(PolicyIndexedEvent event) {
        if (event.ruleChanged()) {
            // onPolicyChanged sends this policy's one alert, saying what moved.
            return;
        }
        List<String> visaTypes = event.visaTypes().stream()
                .map(PolicyImpactRules::normalizeVisaType)
                .distinct()
                .toList();
        if (event.requiresReview()) {
            policyRepository.findById(event.policyId()).ifPresent(policy -> {
                if (recommendationStore != null) {
                    userRepository.findByRole(UserRole.APPLICANT).stream()
                            .filter(User::isActive)
                            .filter(user -> hasCitizenshipConsent(user.getId()) || hasConsentedIssuerDocument(user.getId()))
                            .forEach(user -> recommendationStore.queueExtractionForReview(user, policy,
                                    "Could not confidently extract the proclamation's affected-country rules."));
                }
            });
            return;
        }
        // A reader whose test clock is set before the publication date is alerted once their
        // clock reaches it, by syncToTestClock.
        // Entry restrictions are scoped by their explicitly extracted country or
        // supported document-holder rule. Visa names in the proclamation can be
        // incidental and must not broaden the recipient set.
        List<User> affected;
        if (event.entryRestriction() || event.paTravelDocumentRestriction()) {
            affected = userRepository.findByRole(UserRole.APPLICANT).stream()
                    .filter(User::isActive)
                    .filter(user -> citizenshipMatches(user.getId(), event.affectedCountries())
                            || (event.paTravelDocumentRestriction()
                                    && hasPalestinianAuthorityTravelDocument(user.getId())))
                    .toList();
        } else {
            affected = visaTypes.isEmpty() ? List.of()
                    : userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, visaTypes).stream()
                            .filter(User::isActive)
                            .toList();
        }
        affected = affected.stream()
                .filter(user -> testClock.isPublished(event.publicationDate(), user.getId()))
                .toList();
        String message = buildMessage(event);
        // The rules are visa-specific, so a policy covering several categories affects
        // different fields per reader. Read the policy text once for all of them.
        Map<String, Set<ProfileField>> fieldsByVisaType = visaTypes.isEmpty() ? Map.of()
                : impactRules.affectedFieldsByVisaType(visaTypes, event.title(), event.content());

        // Impact checks read the versioned profile, which holds fields such as the employer
        // that the legacy user row lacks. A user who never saved one falls back to their
        // registration details at version 1.
        Map<Long, UserProfile> profilesByUserId = new HashMap<>();
        userProfileRepository.findAllById(affected.stream().map(User::getId).toList())
                .forEach(profile -> profilesByUserId.put(profile.getUserId(), profile));

        List<Notification> saved = notificationRepository.saveAll(affected.stream()
                .map(user -> toNotification(
                        user,
                        profilesByUserId.getOrDefault(user.getId(), UserProfile.empty(user.getId())),
                        event.policyId(),
                        message,
                        fieldsByVisaType))
                .toList());
        publishAfterCommit(saved);
    }

    /**
     * Alerts the same applicants about a rule change, saying what moved (CS203-86). The alert
     * belongs to the document that caused the change: the new Federal Register document, or the
     * news item behind a dispute. That document gets no other alert, so each reader has one
     * alert per change. A reader who already holds an alert for that document, because the event
     * was delivered twice, is skipped; any other unique-key clash is a bug and fails loudly.
     * Email and the live stream pick the rows up as they do every alert.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPolicyChanged(PolicyChangedEvent event) {
        Long policyId = event.source().policyId();
        List<String> visaTypes = event.visaTypes().stream()
                .map(PolicyImpactRules::normalizeVisaType)
                .distinct()
                .toList();
        Set<Long> alreadyAlerted = notificationRepository.findUserIdsByPolicyId(policyId);
        List<User> matching = userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, visaTypes).stream()
                .filter(user -> testClock.isPublished(event.source().publishedOn(), user.getId()))
                .toList();
        List<User> affected = matching.stream().filter(user -> !alreadyAlerted.contains(user.getId())).toList();
        if (affected.size() < matching.size()) {
            LOGGER.info("Rule {} change {}: {} reader(s) already alerted about policy {} were skipped",
                    event.ruleId(), event.historyId(), matching.size() - affected.size(), policyId);
        }
        String message = truncate(RuleChangeMessage.of(event.visaTypes(), event.ruleTitle(), event.previous(),
                event.current(), event.changes(), event.source().agency()));
        Map<String, Set<ProfileField>> fieldsByVisaType =
                impactRules.affectedFieldsByVisaType(visaTypes, event.primaryTitle(), event.primaryContent());
        Map<Long, UserProfile> profilesByUserId = new HashMap<>();
        userProfileRepository.findAllById(affected.stream().map(User::getId).toList())
                .forEach(profile -> profilesByUserId.put(profile.getUserId(), profile));

        List<Notification> saved = notificationRepository.saveAll(affected.stream()
                .map(user -> toNotification(user, profilesByUserId.getOrDefault(user.getId(), UserProfile.empty(user.getId())),
                        policyId, message, fieldsByVisaType))
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
        UserProfile profile = userProfileRepository.findById(user.getId())
                .orElseGet(() -> UserProfile.empty(user.getId()));
        String visaType = PolicyImpactRules.normalizeVisaType(profile.getVisaType());
        if (user.getRole() != UserRole.APPLICANT
                || (visaType.isEmpty() && !hasCitizenshipConsent(user.getId()))) {
            return new InboxSync(0, removed);
        }
        Set<Long> alreadyNotified = notificationRepository.findPolicyIdsByUserId(user.getId());
        // Only official policies raise alerts, as at ingestion.
        List<Notification> missing = policyRepository
                .findBySourceTypeAndPublicationDateLessThanEqualOrderByPublicationDateAscIdAsc(SourceType.PRIMARY, today)
                .stream()
                .filter(policy -> !alreadyNotified.contains(policy.getId()))
                .map(PolicyIndexedEvent::from)
                .filter(event -> !event.requiresReview())
                .filter(event -> event.entryRestriction() || event.paTravelDocumentRestriction()
                        ? (event.entryRestriction() && citizenshipMatches(user.getId(), event.affectedCountries()))
                                || (event.paTravelDocumentRestriction()
                                        && hasPalestinianAuthorityTravelDocument(user.getId()))
                        : event.visaTypes().stream().map(PolicyImpactRules::normalizeVisaType)
                                .anyMatch(visaType::equals))
                .map(event -> toNotification(user, profile, event.policyId(), syncMessage(event),
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
        Long policyId,
        String message,
        Map<String, Set<ProfileField>> fieldsByVisaType) {
        Set<ProfileField> policyFields = fieldsByVisaType.getOrDefault(
                PolicyImpactRules.normalizeVisaType(profile.getVisaType()),
                Set.of()
        );
        List<ProfileField> toReview = policyFields.stream().filter(field -> field.isFilledIn(profile)).toList();
        if (toReview.isEmpty()) {
            return new Notification(
                    user.getId(),
                    profile.getVersion(),
                    policyId,
                    message,
                    toReview);
        }
        String labels = toReview.stream().map(ProfileField::label).collect(Collectors.joining(", "));
        return new Notification(
                user.getId(),
                profile.getVersion(),
                policyId,
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

    /** The wording a reader would have got at ingestion: the change alert for a document that moved its rule. */
    private String syncMessage(PolicyIndexedEvent event) {
        return ruleStore.alertedChangeFrom(event.policyId())
                .map(change -> truncate(RuleChangeMessage.of(event.visaTypes(), change.ruleTitle(),
                        change.row().previous(), change.row().state(),
                        RuleChange.between(change.row().previous(), change.row().state()),
                        change.row().source().agency())))
                .orElseGet(() -> buildMessage(event));
    }

    /**
     * The generic alert. A document about a rule's effective date, such as a delay notice, gets
     * it only when its new date could not be read, and the Federal Register's effective_on is not
     * that date (for 2021-02665 it is the delayed rule's publication date), so none is stated.
     */
    private String buildMessage(PolicyIndexedEvent event) {
        String audience = event.paTravelDocumentRestriction()
                ? "travelers using a PA-issued travel document"
                : event.entryRestriction() ? "some travelers based on citizenship" : String.join(", ", event.visaTypes());
        String message = "New policy that may affect " + audience + ": " + event.title();
        if (event.effectiveDate() != null && !DateNotice.concernsEffectiveDate(event.title(), event.content())) {
            message += " (effective " + event.effectiveDate() + ")";
        }
        return truncate(message);
    }

    private boolean citizenshipMatches(Long userId, Set<String> affectedCountries) {
        if (affectedCountries == null || affectedCountries.isEmpty() || !hasCitizenshipConsent(userId)) return false;
        UserProfile profile = userProfileRepository.findById(userId).orElse(null);
        if (profile == null || profile.getCountryOfCitizenship() == null || profile.getCountryOfCitizenship().isBlank()) return false;
        String value = profile.getCountryOfCitizenship().trim();
        String code;
        try {
            code = value.length() == 2 ? mygrant.policies.CountryIdentifiers.normalize(value)
                    : java.util.Arrays.stream(java.util.Locale.getISOCountries())
                        .filter(candidate -> new java.util.Locale("", candidate).getDisplayCountry(java.util.Locale.ENGLISH)
                                .equalsIgnoreCase(value))
                        .findFirst().orElse("");
        } catch (IllegalArgumentException invalid) { return false; }
        return affectedCountries.contains(code);
    }

    private boolean hasCitizenshipConsent(Long userId) {
        return consentRepository != null && consentRepository.findByUserId(userId).stream()
                .anyMatch(consent -> consent.getFieldName() == ProfileFieldDefinition.COUNTRY_OF_CITIZENSHIP);
    }

    private boolean hasConsentedIssuerDocument(Long userId) {
        return immigrationDocumentRepository != null
                && !immigrationDocumentRepository.findByUserIdAndPolicyMatchingConsentTrue(userId).isEmpty();
    }

    private boolean hasPalestinianAuthorityTravelDocument(Long userId) {
        return immigrationDocumentRepository != null
                && immigrationDocumentRepository.findByUserIdAndPolicyMatchingConsentTrue(userId).stream()
                        .anyMatch(document -> (document.getDocumentType() == ImmigrationDocumentType.PASSPORT
                                || document.getDocumentType() == ImmigrationDocumentType.TRAVEL_DOCUMENT)
                                && "PA".equals(document.getIssuingAuthority()));
    }

    private String truncate(String message) {
        return message.length() > 1500 ? message.substring(0, 1497) + "..." : message;
    }
}
