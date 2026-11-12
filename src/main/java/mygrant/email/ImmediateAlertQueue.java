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

import mygrant.user.User;
import mygrant.user.UserRepository;

/**
 * Plans email items for newly visible assessments, then queues one email for each item whose user
 * wants it right away (CS203-91: by default, HIGH impact). This runs on a schedule, apart from
 * alert and assessment creation, so nothing about email can delay or fail them.
 */
@Service
public class ImmediateAlertQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImmediateAlertQueue.class);

    private final ImpactEmailAlertRepository alertRepository;
    private final ImpactEmailPlanner planner;
    private final UserRepository userRepository;
    private final EmailPreferenceRepository preferenceRepository;
    private final EmailEligibility eligibility;
    private final EmailOutbox outbox;
    private final EmailProperties properties;

    public ImmediateAlertQueue(ImpactEmailAlertRepository alertRepository, ImpactEmailPlanner planner,
            UserRepository userRepository, EmailPreferenceRepository preferenceRepository,
            EmailEligibility eligibility, EmailOutbox outbox, EmailProperties properties) {
        this.alertRepository = alertRepository;
        this.planner = planner;
        this.userRepository = userRepository;
        this.preferenceRepository = preferenceRepository;
        this.eligibility = eligibility;
        this.outbox = outbox;
        this.properties = properties;
    }

    /**
     * Queues every not yet emailed item from the lookback window whose user wants it right away.
     * The window keeps a first deploy or a settings change from emailing old items. Returns how
     * many emails were queued.
     */
    public int queueDue(Instant now) {
        Instant since = now.minus(properties.immediateLookback());
        planner.planVisible(since);
        List<ImpactEmailAlert> candidates = alertRepository.findNotEmailedSince(since);
        if (candidates.isEmpty()) {
            return 0;
        }
        Map<Long, List<ImpactEmailAlert>> byUser = candidates.stream().collect(
                Collectors.groupingBy(ImpactEmailAlert::getUserId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, User> users = new HashMap<>();
        userRepository.findAllById(byUser.keySet()).forEach(user -> users.put(user.getId(), user));
        Map<Long, EmailPreference> preferences = new HashMap<>();
        preferenceRepository.findAllById(byUser.keySet())
                .forEach(preference -> preferences.put(preference.getUserId(), preference));

        int queued = 0;
        for (Map.Entry<Long, List<ImpactEmailAlert>> entry : byUser.entrySet()) {
            Long userId = entry.getKey();
            queued += queue(users.get(userId), preferences.getOrDefault(userId, EmailPreference.defaults(userId)),
                    entry.getValue(), now);
        }
        return queued;
    }

    /** The same for one account only. */
    public int queueDueFor(Long userId, Instant now) {
        Instant since = now.minus(properties.immediateLookback());
        planner.planVisibleFor(userId, since);
        return queue(userRepository.findById(userId).orElse(null),
                preferenceRepository.findById(userId).orElseGet(() -> EmailPreference.defaults(userId)),
                alertRepository.findNotEmailedSinceForUser(userId, since), now);
    }

    private int queue(User user, EmailPreference preference, List<ImpactEmailAlert> candidates, Instant now) {
        // Verified, active accounts only (EmailEligibility); the planner does not filter accounts.
        if (!eligibility.canReceive(user)) {
            return 0;
        }
        int queued = 0;
        for (ImpactEmailAlert alert : candidates) {
            if (AlertBucket.modeFor(alert, preference) != DeliveryMode.IMMEDIATE) {
                continue;
            }
            try {
                if (outbox.queueImmediate(user.getId(), alert.getId(), now).isPresent()) {
                    queued++;
                }
            } catch (RuntimeException failure) {
                LOGGER.warn("Could not queue the alert email for item {}", alert.getId(), failure);
            }
        }
        return queued;
    }
}
