package mygrant.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Per-user sliding-window limits on the requests that call the AI services (CS203-12, generalised
 * in CS203-176). One mechanism with a separate allowance per kind of request, each configured
 * under {@code mygrant.rate-limits.<allowance>}. Counts live in memory, so each application
 * instance limits on its own and a restart clears them.
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimiter {

    /** The kinds of request with their own allowance, and what a caller over it is told. */
    public enum Allowance {
        /** POST /api/v1/policies/{id}/questions and POST /api/v1/rag/query, shared. */
        QUESTIONS("You are asking questions too quickly. Please wait a moment and try again."),
        /** POST /api/v1/policies/{id}/recommendation. */
        RECOMMENDATIONS("You are requesting assessments too quickly. Please wait a moment and try again."),
        /** POST /api/v1/evaluate (admins). */
        EVALUATIONS("Too many re-evaluations. Please wait a moment and try again.");

        private final String message;

        Allowance(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    private final RateLimitProperties properties;
    private final Clock clock;
    private final Map<Allowance, Map<String, Deque<Instant>>> requests = new EnumMap<>(Allowance.class);
    private final Map<Allowance, Instant> lastSweep = new ConcurrentHashMap<>();

    @Autowired
    public RateLimiter(RateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    RateLimiter(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        for (Allowance allowance : Allowance.values()) {
            requests.put(allowance, new ConcurrentHashMap<>());
            lastSweep.put(allowance, clock.instant());
        }
    }

    /**
     * Records one request by the user against the allowance, or throws when they already made the
     * maximum within its window. A rejected request is not counted.
     */
    public void acquire(Allowance allowance, String user) {
        RateLimitProperties.Limit limit = properties.limit(allowance);
        if (!limit.enabled()) {
            return;
        }
        Instant now = clock.instant();
        Instant windowStart = now.minus(limit.window());
        Map<String, Deque<Instant>> users = requests.get(allowance);
        sweepIfDue(allowance, users, limit.window(), now, windowStart);
        Deque<Instant> times = users.computeIfAbsent(user, key -> new ArrayDeque<>());
        synchronized (times) {
            dropExpired(times, windowStart);
            if (times.size() >= limit.maxRequests()) {
                throw new RateLimitExceededException(Duration.between(windowStart, times.peekFirst()),
                        allowance.message());
            }
            times.addLast(now);
            // Another thread may have swept this user's emptied queue away meanwhile; put it back.
            users.putIfAbsent(user, times);
        }
    }

    private static void dropExpired(Deque<Instant> times, Instant windowStart) {
        while (!times.isEmpty() && !times.peekFirst().isAfter(windowStart)) {
            times.pollFirst();
        }
    }

    /** Once per window, forget users with no recent requests so the maps do not grow forever. */
    private void sweepIfDue(Allowance allowance, Map<String, Deque<Instant>> users, Duration window,
            Instant now, Instant windowStart) {
        if (Duration.between(lastSweep.get(allowance), now).compareTo(window) < 0) {
            return;
        }
        lastSweep.put(allowance, now);
        users.entrySet().removeIf(entry -> {
            Deque<Instant> times = entry.getValue();
            synchronized (times) {
                dropExpired(times, windowStart);
                return times.isEmpty();
            }
        });
    }
}
