package mygrant.email;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import mygrant.notifications.Notification;
import mygrant.notifications.NotificationRepository;
import mygrant.user.User;
import mygrant.user.UserRepository;

/**
 * Builds the daily digest: one email per user holding every unread alert no other email has
 * carried, from the buckets the user set to DIGEST. A user with nothing new gets no email
 * and no outbox row. Alerts are claimed by their digest, so none appears in two digests.
 */
@Service
public class DailyDigestService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DailyDigestService.class);

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final EmailPreferenceRepository preferenceRepository;
    private final EmailEligibility eligibility;
    private final EmailOutbox outbox;
    private final EmailProperties properties;

    public DailyDigestService(NotificationRepository notificationRepository, UserRepository userRepository,
            EmailPreferenceRepository preferenceRepository, EmailEligibility eligibility, EmailOutbox outbox,
            EmailProperties properties) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.preferenceRepository = preferenceRepository;
        this.eligibility = eligibility;
        this.outbox = outbox;
        this.properties = properties;
    }

    /** The digest date for a run at that instant: the local date in the digest zone. */
    public LocalDate today(Instant now) {
        return LocalDate.ofInstant(now, properties.digestZone());
    }

    /**
     * Queues the digests for that date and returns how many it created. Alerts older than
     * the lookback window stay in-app only, so a first deploy does not mail anyone their
     * whole inbox.
     */
    public int buildDigests(LocalDate digestDate, Instant now) {
        List<Notification> candidates = notificationRepository.findNotEmailedSince(now.minus(properties.digestLookback()));
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

        int created = 0;
        for (Map.Entry<Long, List<Notification>> entry : byUser.entrySet()) {
            Long userId = entry.getKey();
            EmailPreference preference = preferences.getOrDefault(userId, EmailPreference.defaults(userId));
            if (queue(users.get(userId), preference, entry.getValue(), digestDate, now).isPresent()) {
                created++;
            }
        }
        return created;
    }

    /** The same for one account only. */
    public Optional<UUID> buildDigestFor(Long userId, LocalDate digestDate, Instant now) {
        return queue(userRepository.findById(userId).orElse(null),
                preferenceRepository.findById(userId).orElseGet(() -> EmailPreference.defaults(userId)),
                notificationRepository.findNotEmailedSinceForUser(userId, now.minus(properties.digestLookback())),
                digestDate, now);
    }

    private Optional<UUID> queue(User user, EmailPreference preference, List<Notification> candidates,
            LocalDate digestDate, Instant now) {
        if (!eligibility.canReceive(user)) {
            return Optional.empty();
        }
        List<Long> items = candidates.stream()
                .filter(notification -> AlertBucket.of(notification).modeIn(preference) == DeliveryMode.DIGEST)
                .map(Notification::getId)
                .toList();
        if (items.isEmpty()) {
            return Optional.empty();
        }
        try {
            return outbox.queueDigest(user.getId(), digestDate, items, now);
        } catch (RuntimeException failure) {
            LOGGER.warn("Could not queue the {} digest for user {}", digestDate, user.getId(), failure);
            return Optional.empty();
        }
    }
}
