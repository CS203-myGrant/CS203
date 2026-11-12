package mygrant.ai;

import java.time.Duration;
import java.util.Optional;

import org.springframework.ai.retry.TransientAiException;

/**
 * A 429 or 5xx answer from an AI provider called through Spring AI, with the provider's
 * Retry-After if it sent one, so {@link AiCallRetry} can honour it.
 */
public class RetryableAiException extends TransientAiException {
    private final int status;
    private final transient Duration retryAfter;

    public RetryableAiException(String message, int status, Duration retryAfter) {
        super(message);
        this.status = status;
        this.retryAfter = retryAfter;
    }

    public int status() {
        return status;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
