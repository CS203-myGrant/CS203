package mygrant.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * CS203-12: a per-user sliding-window limit on policy questions, shared by
 * {@code POST /api/v1/policies/{id}/questions} and {@code POST /api/v1/rag/query} because both
 * call the AI services. Counts live in memory, so each application instance limits on its own
 * and a restart clears them.
 */
@Component
@EnableConfigurationProperties(QuestionRateLimitProperties.class)
public class QuestionRateLimiter {
    private final QuestionRateLimitProperties properties;
    private final Clock clock;
    private final Map<String, Deque<Instant>> requests = new ConcurrentHashMap<>();
    private volatile Instant lastSweep;

    @Autowired
    public QuestionRateLimiter(QuestionRateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    QuestionRateLimiter(QuestionRateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.lastSweep = clock.instant();
    }

    /**
     * Records one question for the user, or throws when they already asked the maximum within
     * the window. A rejected request is not counted.
     */
    public void acquire(String user) {
        if (!properties.enabled()) {
            return;
        }
        Instant now = clock.instant();
        Instant windowStart = now.minus(properties.window());
        sweepIfDue(now, windowStart);
        Deque<Instant> times = requests.computeIfAbsent(user, key -> new ArrayDeque<>());
        synchronized (times) {
            dropExpired(times, windowStart);
            if (times.size() >= properties.maxRequests()) {
                throw new RateLimitExceededException(Duration.between(windowStart, times.peekFirst()));
            }
            times.addLast(now);
            // Another thread may have swept this user's emptied queue away meanwhile; put it back.
            requests.putIfAbsent(user, times);
        }
    }

    private static void dropExpired(Deque<Instant> times, Instant windowStart) {
        while (!times.isEmpty() && !times.peekFirst().isAfter(windowStart)) {
            times.pollFirst();
        }
    }

    /** Once per window, forget users with no recent questions so the map does not grow forever. */
    private void sweepIfDue(Instant now, Instant windowStart) {
        if (Duration.between(lastSweep, now).compareTo(properties.window()) < 0) {
            return;
        }
        lastSweep = now;
        requests.entrySet().removeIf(entry -> {
            Deque<Instant> times = entry.getValue();
            synchronized (times) {
                dropExpired(times, windowStart);
                return times.isEmpty();
            }
        });
    }
}
