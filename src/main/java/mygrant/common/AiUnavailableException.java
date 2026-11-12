package mygrant.common;

import java.time.Duration;

/**
 * CS203-174: an AI provider still failed after its retries, so a user-facing request cannot be
 * answered now. Answered with 503 and a Retry-After header; background work records the result
 * as unavailable instead of throwing this.
 */
public class AiUnavailableException extends RuntimeException {
    public static final String MESSAGE = "The AI service is temporarily unavailable. Please try again later.";
    public static final Duration RETRY_AFTER = Duration.ofSeconds(30);

    public AiUnavailableException() {
        super(MESSAGE);
    }
}
