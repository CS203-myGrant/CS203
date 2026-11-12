package mygrant.common;

import java.time.Duration;

/** The caller has used up their allowance; answered with 429 and a Retry-After header. */
public class RateLimitExceededException extends RuntimeException {
    private final Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        this(retryAfter, RateLimiter.Allowance.QUESTIONS.message());
    }

    /** CS203-176: each allowance tells the caller which kind of request to slow down. */
    public RateLimitExceededException(Duration retryAfter, String message) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }

    /** Whole seconds, rounded up and at least one, as the Retry-After header expects. */
    public long getRetryAfterSeconds() {
        long seconds = retryAfter.toSeconds() + (retryAfter.toNanosPart() > 0 ? 1 : 0);
        return Math.max(1, seconds);
    }
}
