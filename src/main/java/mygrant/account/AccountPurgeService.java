package mygrant.account;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mygrant.email.EmailPreferenceRepository;
import mygrant.feedback.FeedbackConversationRepository;
import mygrant.feedback.FeedbackMessageRepository;
import mygrant.notifications.NotificationRepository;
import mygrant.notifications.NotificationStreamRegistry;
import mygrant.user.ImmigrationDocumentRepository;
import mygrant.user.User;
import mygrant.user.UserProfileRepository;
import mygrant.user.UserRepository;

/** Permanently removes user-owned data and retains only an anonymised account tombstone. */
@Service
public class AccountPurgeService {

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final ImmigrationDocumentRepository immigrationDocumentRepository;
    private final NotificationRepository notificationRepository;
    private final AccountDeletionRequestRepository deletionRequestRepository;
    private final AccountDeletionAuditRepository auditRepository;
    private final NotificationStreamRegistry notificationStreamRegistry;
    private final FeedbackConversationRepository feedbackConversationRepository;
    private final FeedbackMessageRepository feedbackMessageRepository;
    private final EmailPreferenceRepository emailPreferenceRepository;

    public AccountPurgeService(UserRepository userRepository,
            UserProfileRepository userProfileRepository,
            ImmigrationDocumentRepository immigrationDocumentRepository,
            NotificationRepository notificationRepository,
            AccountDeletionRequestRepository deletionRequestRepository,
            AccountDeletionAuditRepository auditRepository,
            NotificationStreamRegistry notificationStreamRegistry,
            FeedbackConversationRepository feedbackConversationRepository,
            FeedbackMessageRepository feedbackMessageRepository,
            EmailPreferenceRepository emailPreferenceRepository) {
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.immigrationDocumentRepository = immigrationDocumentRepository;
        this.notificationRepository = notificationRepository;
        this.deletionRequestRepository = deletionRequestRepository;
        this.auditRepository = auditRepository;
        this.notificationStreamRegistry = notificationStreamRegistry;
        this.feedbackConversationRepository = feedbackConversationRepository;
        this.feedbackMessageRepository = feedbackMessageRepository;
        this.emailPreferenceRepository = emailPreferenceRepository;
    }

    /**
     * Purges all currently persisted user-owned data. Repeating this operation is safe:
     * deletes of absent rows are no-ops and the audit row is unique per account.
     */
    @Transactional
    public void purge(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return;
        }

        Instant deletedAt = user.getDeletedAt() == null ? Instant.now() : user.getDeletedAt();

        notificationRepository.deleteAllByUserId(userId);
        emailPreferenceRepository.deleteById(userId);
        userProfileRepository.deleteById(userId);
        immigrationDocumentRepository.deleteAllByUserId(userId);
        deletionRequestRepository.deleteAllByUserId(userId);
        // Remove messages authored by this user even when they belong to another
        // user's conversation, then remove the user's own conversations. The
        // latter cascades to their remaining messages at the database level.
        feedbackMessageRepository.deleteAllBySenderId(userId);
        feedbackConversationRepository.deleteAllByUserId(userId);

        user.anonymizeAndDeactivate(deletedAt);
        userRepository.save(user);

        if (!auditRepository.existsByUserId(userId)) {
            auditRepository.save(new AccountDeletionAudit(userId, deletedAt));
        }

        notificationStreamRegistry.closeAll(userId);
    }
}
