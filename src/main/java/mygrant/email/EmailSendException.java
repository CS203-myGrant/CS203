package mygrant.email;

/**
 * A failed send. The message is short and safe to store and log: an HTTP status and the
 * provider's error name, or an exception type, never the recipient, body or API key.
 */
public class EmailSendException extends RuntimeException {

    private final boolean retryable;
    private final String errorName;

    public EmailSendException(boolean retryable, String errorName, String message) {
        super(message);
        this.retryable = retryable;
        this.errorName = errorName;
    }

    /** True for failures that may succeed later: timeouts, 5xx, rate limits. */
    public boolean isRetryable() {
        return retryable;
    }

    /** The provider's error name, such as {@code rate_limit_exceeded}; null when it gave none. */
    public String getErrorName() {
        return errorName;
    }
}
