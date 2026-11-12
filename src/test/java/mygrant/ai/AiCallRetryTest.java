package mygrant.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/** CS203-174: which AI failures are retried, how long it waits, and when it gives up. */
class AiCallRetryTest {

    private static final Instant NOW = Instant.parse("2026-11-12T09:00:00Z");
    private final List<Duration> waits = new ArrayList<>();
    private final AiCallRetry retry = new AiCallRetry(
            new AiResilienceProperties.Retry(3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(8), Duration.ofSeconds(20)),
            waits::add, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aTimeoutIsRetriedWithExponentialBackoffUntilItSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        String result = retry.call("test", () -> {
            if (calls.incrementAndGet() < 3) throw new ResourceAccessException("Read timed out");
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
        assertThat(waits).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void itGivesUpAfterTheConfiguredAttemptsAndThrowsTheLastFailure() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> retry.call("test", () -> {
            calls.incrementAndGet();
            throw server(HttpStatus.BAD_GATEWAY, null);
        })).isInstanceOf(HttpServerErrorException.class);

        assertThat(calls).hasValue(3);
        assertThat(waits).hasSize(2);
    }

    @Test
    void aRateLimitHonoursRetryAfterInSecondsOrAsADate() {
        assertThat(retry.delayAfter(1, client(HttpStatus.TOO_MANY_REQUESTS, "5"))).contains(Duration.ofSeconds(5));
        assertThat(retry.delayAfter(1, client(HttpStatus.TOO_MANY_REQUESTS, "Thu, 12 Nov 2026 09:00:07 GMT")))
                .contains(Duration.ofSeconds(7));
        // Never sooner than the backoff, and without the header the backoff applies.
        assertThat(retry.delayAfter(3, client(HttpStatus.TOO_MANY_REQUESTS, "1"))).contains(Duration.ofSeconds(4));
        assertThat(retry.delayAfter(1, client(HttpStatus.TOO_MANY_REQUESTS, null))).contains(Duration.ofSeconds(1));
        assertThat(retry.delayAfter(1, server(HttpStatus.SERVICE_UNAVAILABLE, "3"))).contains(Duration.ofSeconds(3));
    }

    @Test
    void aRetryAfterLongerThanTheMaximumFailsAtOnce() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> retry.call("test", () -> {
            calls.incrementAndGet();
            throw client(HttpStatus.TOO_MANY_REQUESTS, "120");
        })).isInstanceOf(HttpClientErrorException.class);

        assertThat(calls).hasValue(1);
        assertThat(waits).isEmpty();
    }

    @Test
    void otherClientErrorsAndUnexpectedFailuresAreNeverRetried() {
        for (HttpStatus status : List.of(HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND)) {
            assertThat(retry.delayAfter(1, client(status, "1"))).as(status.toString()).isEmpty();
        }
        assertThat(retry.delayAfter(1, new IllegalStateException("bad JSON"))).isEmpty();
        assertThat(retry.delayAfter(1, new NonTransientAiException("HTTP 401"))).isEmpty();
    }

    @Test
    void springAiProviderErrorsFollowTheSameRule() {
        assertThat(retry.delayAfter(1, new RetryableAiException("HTTP 429", 429, Duration.ofSeconds(6))))
                .contains(Duration.ofSeconds(6));
        assertThat(retry.delayAfter(2, new RetryableAiException("HTTP 500", 500, null))).contains(Duration.ofSeconds(2));
        assertThat(retry.delayAfter(1, new RetryableAiException("HTTP 429", 429, Duration.ofMinutes(5)))).isEmpty();
    }

    @Test
    void backoffIsCappedAndUnreadableRetryAfterIsIgnored() {
        assertThat(retry.backoff(10)).isEqualTo(Duration.ofSeconds(8));
        assertThat(AiCallRetry.parseRetryAfter("soon", Clock.systemUTC())).isEmpty();
        assertThat(AiCallRetry.parseRetryAfter("-1", Clock.systemUTC())).isEmpty();
        assertThat(AiCallRetry.parseRetryAfter("Thu, 12 Nov 2026 08:00:00 GMT", Clock.fixed(NOW, ZoneOffset.UTC)))
                .contains(Duration.ZERO);
    }

    @Test
    void handBuiltClientsMakeOneAttempt() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> AiCallRetry.once().call("test", () -> {
            calls.incrementAndGet();
            throw new ResourceAccessException("Read timed out");
        })).isInstanceOf(ResourceAccessException.class);
        assertThat(calls).hasValue(1);
    }

    private static HttpClientErrorException client(HttpStatus status, String retryAfter) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), headers(retryAfter),
                new byte[0], StandardCharsets.UTF_8);
    }

    private static HttpServerErrorException server(HttpStatus status, String retryAfter) {
        return (HttpServerErrorException) HttpServerErrorException.create(status, status.getReasonPhrase(),
                headers(retryAfter), new byte[0], StandardCharsets.UTF_8);
    }

    private static HttpHeaders headers(String retryAfter) {
        HttpHeaders headers = new HttpHeaders();
        if (retryAfter != null) headers.set(HttpHeaders.RETRY_AFTER, retryAfter);
        return headers;
    }
}
