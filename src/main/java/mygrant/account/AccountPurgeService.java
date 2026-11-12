package mygrant.account;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import mygrant.actions.ActionItemService;
import mygrant.email.EmailDeliveryRepository;
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
    private final EmailDeliveryRepository emailDeliveryRepository;
    private final ActionItemService actionItemService;
    private final JdbcTemplate forecastJdbc;

    @Autowired
    public AccountPurgeService(UserRepository userRepository,
            UserProfileRepository userProfileRepository,
            ImmigrationDocumentRepository immigrationDocumentRepository,
            NotificationRepository notificationRepository,
            AccountDeletionRequestRepository deletionRequestRepository,
            AccountDeletionAuditRepository auditRepository,
            NotificationStreamRegistry notificationStreamRegistry,
            FeedbackConversationRepository feedbackConversationRepository,
            FeedbackMessageRepository feedbackMessageRepository,
            EmailPreferenceRepository emailPreferenceRepository,
            EmailDeliveryRepository emailDeliveryRepository,
            ActionItemService actionItemService,
            JdbcTemplate forecastJdbc) {
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
        this.emailDeliveryRepository = emailDeliveryRepository;
        this.actionItemService = actionItemService;
        this.forecastJdbc = forecastJdbc;
    }

    public AccountPurgeService(UserRepository users, UserProfileRepository profiles,
            ImmigrationDocumentRepository documents, NotificationRepository notifications,
            AccountDeletionRequestRepository requests, AccountDeletionAuditRepository audit,
            NotificationStreamRegistry streams, FeedbackConversationRepository conversations,
            FeedbackMessageRepository messages, EmailPreferenceRepository preferences,
            EmailDeliveryRepository deliveries, ActionItemService actions) {
        this(users, profiles, documents, notifications, requests, audit, streams, conversations,
                messages, preferences, deliveries, actions, null);
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

        if (forecastJdbc != null) {
            // CS203-91: the planned alert emails, which quote assessment summaries.
            forecastJdbc.update("DELETE FROM impact_email_alerts WHERE user_id=?", userId);
        }
        notificationRepository.deleteAllByUserId(userId);
        // After the notifications, which reference their email.
        emailDeliveryRepository.deleteAllByUserId(userId);
        emailPreferenceRepository.deleteById(userId);
        // Before the assessments they were generated from.
        actionItemService.deleteAllByUserId(userId);
        if (forecastJdbc != null) {
            forecastJdbc.update("DELETE FROM forecast_deadline_alerts WHERE user_id=?", userId);
            forecastJdbc.update("DELETE FROM forecast_series_selections WHERE user_id=?", userId);
            forecastJdbc.update("DELETE FROM visa_bulletin_alerts WHERE user_id=?", userId);
            forecastJdbc.update("DELETE FROM visa_bulletin_subscriptions WHERE user_id=?", userId);
        }
        userProfileRepository.deleteRecommendationsByUserId(userId);
        userProfileRepository.deleteApplicationProgressByUserId(userId);
        userProfileRepository.deleteQuestionHistoryByUserId(userId);
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
