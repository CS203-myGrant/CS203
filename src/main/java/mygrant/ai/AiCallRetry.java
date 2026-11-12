package mygrant.ai;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * CS203-174: the one retry rule for AI provider calls. A call is retried, a bounded number of
 * times with exponential backoff, only when it timed out or the provider answered 429 or 5xx.
 * A Retry-After header is honoured; one asking for longer than the configured maximum ends the
 * call at once, since waiting that long would hold a user's request or a worker. Every other
 * failure, such as a refused connection, a 400 or a 401, is returned straight away.
 */
public final class AiCallRetry {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiCallRetry.class);

    /** Waits between attempts; tests pass one that records instead of sleeping. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final AiResilienceProperties.Retry settings;
    private final Sleeper sleeper;
    private final Clock clock;

    public AiCallRetry(AiResilienceProperties.Retry settings, Sleeper sleeper, Clock clock) {
        this.settings = settings;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    /** One attempt and no waiting: for clients built by hand without the application's settings. */
    public static AiCallRetry once() {
        return new AiCallRetry(AiResilienceProperties.Retry.once(), duration -> { }, Clock.systemUTC());
    }

    public int maxAttempts() {
        return settings.maxAttempts();
    }

    /** Runs the call, retrying it under the rule above; the last failure is thrown unchanged. */
    public <T> T call(String operation, Supplier<T> call) {
        for (int attempt = 1; ; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException failure) {
                Optional<Duration> delay = attempt < settings.maxAttempts()
                        ? delayAfter(attempt, failure) : Optional.empty();
                if (delay.isEmpty()) {
                    throw failure;
                }
                LOGGER.warn("{} failed on attempt {} of {} ({}); retrying in {} ms", operation, attempt,
                        settings.maxAttempts(), describe(failure), delay.get().toMillis());
                try {
                    sleeper.sleep(delay.get());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw failure;
                }
            }
        }
    }

    /**
     * How long to wait after this many failed attempts before trying again, or empty when the
     * failure is not worth retrying. The attempt limit is the caller's to check.
     */
    public Optional<Duration> delayAfter(int failedAttempts, Throwable failure) {
        Duration backoff = backoff(failedAttempts);
        if (failure instanceof ResourceAccessException) {
            return timedOut(failure) ? Optional.of(backoff) : Optional.empty();
        }
        int status;
        Optional<Duration> retryAfter;
        if (failure instanceof RestClientResponseException http) {
            status = http.getStatusCode().value();
            HttpHeaders headers = http.getResponseHeaders();
            retryAfter = parseRetryAfter(headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER), clock);
        } else if (failure instanceof RetryableAiException ai) {
            status = ai.status();
            retryAfter = ai.retryAfter();
        } else {
            return Optional.empty();
        }
        if (status != 429 && (status < 500 || status > 599)) {
            return Optional.empty();
        }
        if (retryAfter.isPresent()) {
            if (retryAfter.get().compareTo(settings.maxRetryAfter()) > 0) {
                return Optional.empty();
            }
            return Optional.of(retryAfter.get().compareTo(backoff) > 0 ? retryAfter.get() : backoff);
        }
        return Optional.of(backoff);
    }

    /** A connect or read timeout from either HTTP client; HttpConnectTimeoutException is one too. */
    private static boolean timedOut(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    Duration backoff(int failedAttempts) {
        double millis = settings.initialBackoff().toMillis() * Math.pow(settings.multiplier(), failedAttempts - 1);
        long capped = (long) Math.min(millis, settings.maxBackoff().toMillis());
        return Duration.ofMillis(Math.max(0, capped));
    }

    /** Retry-After as delay-seconds or an HTTP date (RFC 9110); empty when absent or unreadable. */
    public static Optional<Duration> parseRetryAfter(String value, Clock clock) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            return seconds < 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds));
        } catch (NumberFormatException notSeconds) {
            try {
                Instant at = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration until = Duration.between(clock.instant(), at);
                return Optional.of(until.isNegative() ? Duration.ZERO : until);
            } catch (DateTimeParseException unreadable) {
                return Optional.empty();
            }
        }
    }

    private static String describe(Throwable failure) {
        if (failure instanceof RestClientResponseException http) return "HTTP " + http.getStatusCode().value();
        if (failure instanceof RetryableAiException ai) return "HTTP " + ai.status();
        return failure.getClass().getSimpleName();
    }
}
