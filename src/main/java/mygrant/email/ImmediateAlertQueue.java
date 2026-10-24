package mygrant.email;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import mygrant.notifications.Notification;
import mygrant.notifications.NotificationRepository;
import mygrant.user.User;
import mygrant.user.UserRepository;

/**
 * Finds new alerts whose user wants them emailed right away and queues one email each.
 * This runs on a schedule, apart from notification creation, so nothing about email can
 * delay or fail an alert.
 */
@Service
public class ImmediateAlertQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImmediateAlertQueue.class);

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final EmailPreferenceRepository preferenceRepository;
    private final EmailEligibility eligibility;
    private final EmailOutbox outbox;
    private final EmailProperties properties;

    public ImmediateAlertQueue(NotificationRepository notificationRepository, UserRepository userRepository,
            EmailPreferenceRepository preferenceRepository, EmailEligibility eligibility, EmailOutbox outbox,
            EmailProperties properties) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.preferenceRepository = preferenceRepository;
        this.eligibility = eligibility;
        this.outbox = outbox;
        this.properties = properties;
    }

    /**
     * Queues every unread, not yet emailed alert from the lookback window whose bucket the
     * user set to IMMEDIATE. The window keeps a first deploy or a settings change from
     * emailing old alerts. Returns how many emails were queued.
     */
    public int queueDue(Instant now) {
        List<Notification> candidates = notificationRepository.findNotEmailedSince(
                now.minus(properties.immediateLookback()));
        if (candidates.isEmpty()) {
            return 0;
        }
        Map<Long, List<Notification>> byUser = candidates.stream().collect(
                Collectors.groupingBy(Notification::getUserId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, User> users = new HashMap<>();
        userRepository.findAllById(byUser.keySet()).forEach(user -> users.put(user.getId(), user));
        Map<Long, EmailPreference> preferences = new HashMap<>();
        preferenceRepository.findAllById(byUser.keySet())
                .forEach(preference -> preferences.put(preference.getUserId(), preference));

        int queued = 0;
        for (Map.Entry<Long, List<Notification>> entry : byUser.entrySet()) {
            Long userId = entry.getKey();
            // The recipient query behind notifications does not filter inactive users.
            if (!eligibility.canReceive(users.get(userId))) {
                continue;
            }
            EmailPreference preference = preferences.getOrDefault(userId, EmailPreference.defaults(userId));
            for (Notification notification : entry.getValue()) {
                if (AlertBucket.of(notification).modeIn(preference) != DeliveryMode.IMMEDIATE) {
                    continue;
                }
                try {
                    if (outbox.queueImmediate(userId, notification.getId(), now).isPresent()) {
                        queued++;
                    }
                } catch (RuntimeException failure) {
                    LOGGER.warn("Could not queue the alert email for notification {}", notification.getId(), failure);
                }
            }
        }
        return queued;
    }
}
