package mygrant.email;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import mygrant.notifications.Notification;
import mygrant.notifications.NotificationRepository;
import mygrant.user.User;
import mygrant.user.UserRepository;

/**
 * Sends due emails from the outbox. Each email is claimed in one short transaction, sent
 * with no transaction or row lock held, and its outcome recorded in another; this class is
 * deliberately not transactional. The delivery id is the provider idempotency key.
 *
 * <p>Before the first attempt the email may still drop alerts the user no longer wants
 * emailed this way, which releases them. After any attempt its contents are frozen: a retry
 * sends exactly the same alerts, or the whole email is SKIPPED if the user has opted out or
 * can no longer receive email. A frozen email never releases its alerts, because an earlier
 * attempt may have reached the user and releasing them could send them twice.
 */
@Service
public class EmailDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmailDispatcher.class);
    private static final int MAX_ERROR_LENGTH = 300;

    public enum Outcome { SENT, RETRYING, FAILED, SKIPPED, NOT_CLAIMED }

    public record Result(int sent, int retrying, int failed, int skipped) {
        public static final Result NONE = new Result(0, 0, 0, 0);

        Result plus(Outcome outcome) {
            return switch (outcome) {
                case SENT -> new Result(sent + 1, retrying, failed, skipped);
                case RETRYING -> new Result(sent, retrying + 1, failed, skipped);
                case FAILED -> new Result(sent, retrying, failed + 1, skipped);
                case SKIPPED -> new Result(sent, retrying, failed, skipped + 1);
                case NOT_CLAIMED -> this;
            };
        }
    }

    private final EmailDeliveryRepository deliveryRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final EmailPreferenceRepository preferenceRepository;
    private final EmailEligibility eligibility;
    private final AlertEmailRenderer renderer;
    private final EmailSender sender;
    private final EmailProperties properties;

    public EmailDispatcher(EmailDeliveryRepository deliveryRepository, NotificationRepository notificationRepository,
            UserRepository userRepository, EmailPreferenceRepository preferenceRepository,
            EmailEligibility eligibility, AlertEmailRenderer renderer, EmailSender sender,
            EmailProperties properties) {
        this.deliveryRepository = deliveryRepository;
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.preferenceRepository = preferenceRepository;
        this.eligibility = eligibility;
        this.renderer = renderer;
        this.sender = sender;
        this.properties = properties;
    }

    /** Recovers interrupted sends, then sends up to one batch of due emails. */
    public Result dispatchDue(Instant now) {
        // Without credentials nothing is claimed, so no attempts are used up.
        if (!sender.isConfigured()) {
            return Result.NONE;
        }
        recoverStuck(now);
        Result result = Result.NONE;
        for (UUID id : deliveryRepository.findDueIds(now, PageRequest.of(0, properties.batchSize()))) {
            try {
                result = result.plus(dispatch(id, now));
            } catch (RuntimeException failure) {
                // The outcome could not be recorded; the row stays SENDING until stuck recovery.
                LOGGER.warn("Could not record the outcome of alert email {}", id, failure);
            }
        }
        return result;
    }

    /** Sends one email if this call wins its claim; NOT_CLAIMED if it is not due or another run has it. */
    public Outcome dispatch(UUID id, Instant now) {
        if (!sender.isConfigured() || deliveryRepository.claim(id, now) == 0) {
            return Outcome.NOT_CLAIMED;
        }
        EmailDelivery delivery = deliveryRepository.findById(id).orElse(null);
        if (delivery == null) {
            return Outcome.NOT_CLAIMED; // removed by an account purge since the claim
        }
        try {
            return send(delivery, now);
        } catch (RuntimeException unexpected) {
            // Rendering or the database failed around the send. A retry reuses the key, so
            // the provider drops it if the first request did go out.
            LOGGER.warn("Alert email {} hit an unexpected error", id, unexpected);
            return retryOrFail(delivery, "Unexpected " + unexpected.getClass().getSimpleName(), now);
        }
    }

    private Outcome send(EmailDelivery delivery, Instant now) {
        UUID id = delivery.getId();
        boolean firstAttempt = delivery.getAttempts() == 1;
        User user = userRepository.findById(delivery.getUserId()).orElse(null);
        List<Notification> items = notificationRepository.findByEmailDeliveryIdOrderByCreatedAtAscIdAsc(id);
        EmailPreference preference = preferenceRepository.findById(delivery.getUserId())
                .orElseGet(() -> EmailPreference.defaults(delivery.getUserId()));
        DeliveryMode mode = delivery.getKind() == DeliveryKind.IMMEDIATE ? DeliveryMode.IMMEDIATE : DeliveryMode.DIGEST;
        List<Notification> stillWanted = items.stream()
                .filter(notification -> AlertBucket.of(notification).modeIn(preference) == mode)
                .toList();
        boolean canReceive = eligibility.canReceive(user);

        if (firstAttempt) {
            if (!canReceive || stillWanted.isEmpty()) {
                release(id, items);
                return skip(id, canReceive ? "Turned off by the user" : "Recipient can no longer receive email");
            }
            if (stillWanted.size() < items.size()) {
                release(id, items.stream().filter(item -> !stillWanted.contains(item)).toList());
                items = stillWanted;
            }
        } else if (!canReceive || items.isEmpty() || stillWanted.size() < items.size()) {
            return skip(id, canReceive ? "Turned off by the user before a retry" : "Recipient can no longer receive email");
        }

        EmailMessage message = renderer.render(delivery, user, items);
        try {
            String providerMessageId = sender.send(message);
            deliveryRepository.markSent(id, providerMessageId, Instant.now());
            LOGGER.info("Sent {} alert email {} on attempt {}", delivery.getKind(), id, delivery.getAttempts());
            return Outcome.SENT;
        } catch (EmailSendException failure) {
            if ("invalid_idempotent_request".equals(failure.getErrorName())) {
                // The key was already used with different contents; resending under a new key
                // could deliver twice, so this email stops here.
                LOGGER.error("Alert email {} was refused as a changed retry of an earlier request; not resending", id);
                return fail(id, failure.getMessage());
            }
            if (!failure.isRetryable()) {
                LOGGER.warn("Alert email {} was refused: {}", id, failure.getMessage());
                return fail(id, failure.getMessage());
            }
            return retryOrFail(delivery, failure.getMessage(), now);
        }
    }

    private Outcome retryOrFail(EmailDelivery delivery, String error, Instant now) {
        if (delivery.getAttempts() >= properties.maxAttempts()) {
            LOGGER.warn("Alert email {} failed after {} attempts: {}", delivery.getId(), delivery.getAttempts(), error);
            return fail(delivery.getId(), error);
        }
        Instant retryAt = now.plus(properties.backoffAfter(delivery.getAttempts()));
        deliveryRepository.markRetry(delivery.getId(), retryAt, truncate(error));
        LOGGER.info("Alert email {} attempt {} failed ({}); retrying at {}",
                delivery.getId(), delivery.getAttempts(), error, retryAt);
        return Outcome.RETRYING;
    }

    private Outcome fail(UUID id, String error) {
        deliveryRepository.markFinished(id, DeliveryStatus.FAILED, truncate(error));
        return Outcome.FAILED;
    }

    private Outcome skip(UUID id, String reason) {
        deliveryRepository.markFinished(id, DeliveryStatus.SKIPPED, reason);
        LOGGER.info("Skipped alert email {}: {}", id, reason);
        return Outcome.SKIPPED;
    }

    private void release(UUID id, List<Notification> notifications) {
        if (!notifications.isEmpty()) {
            notificationRepository.releaseFromDelivery(id, notifications.stream().map(Notification::getId).toList());
        }
    }

    private void recoverStuck(Instant now) {
        Instant cutoff = now.minus(properties.stuckAfter());
        int failed = deliveryRepository.failStuck(cutoff, properties.maxAttempts());
        int requeued = deliveryRepository.requeueStuck(cutoff, properties.maxAttempts(), now);
        if (failed + requeued > 0) {
            LOGGER.warn("Found {} alert emails interrupted while sending: {} queued again, {} out of attempts",
                    failed + requeued, requeued, failed);
        }
    }

    private static String truncate(String error) {
        return error == null || error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
