package mygrant.common;

import static mygrant.common.RateLimiter.Allowance.EVALUATIONS;
import static mygrant.common.RateLimiter.Allowance.QUESTIONS;
import static mygrant.common.RateLimiter.Allowance.RECOMMENDATIONS;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-11T09:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration duration) { now = now.plus(duration); }
    }

    private final MutableClock clock = new MutableClock();

    private static final RateLimitProperties.Limit DEFAULT =
            new RateLimitProperties.Limit(true, 10, Duration.ofMinutes(1));

    /** A limiter whose QUESTIONS allowance is the one given. */
    private RateLimiter limiter(boolean enabled, int max, Duration window) {
        return new RateLimiter(new RateLimitProperties(new RateLimitProperties.Limit(enabled, max, window),
                DEFAULT, DEFAULT), clock);
    }

    @Test
    void allowsUpToTheLimitThenRejectsWithTimeUntilTheOldestExpires() {
        RateLimiter limiter = limiter(true, 3, Duration.ofMinutes(1));
        limiter.acquire(QUESTIONS, "a@example.org");
        clock.advance(Duration.ofSeconds(10));
        limiter.acquire(QUESTIONS, "a@example.org");
        limiter.acquire(QUESTIONS, "a@example.org");

        assertThatThrownBy(() -> limiter.acquire(QUESTIONS, "a@example.org"))
                .isInstanceOfSatisfying(RateLimitExceededException.class, exception -> {
                    assertThat(exception.getRetryAfter()).isEqualTo(Duration.ofSeconds(50));
                    assertThat(exception.getRetryAfterSeconds()).isEqualTo(50);
                });
    }

    @Test
    void slidingWindowFreesASlotWhenTheOldestRequestExpires() {
        RateLimiter limiter = limiter(true, 2, Duration.ofMinutes(1));
        limiter.acquire(QUESTIONS, "a@example.org");
        clock.advance(Duration.ofSeconds(30));
        limiter.acquire(QUESTIONS, "a@example.org");
        assertThatThrownBy(() -> limiter.acquire(QUESTIONS, "a@example.org")).isInstanceOf(RateLimitExceededException.class);

        clock.advance(Duration.ofSeconds(30));
        assertThatCode(() -> limiter.acquire(QUESTIONS, "a@example.org")).doesNotThrowAnyException();
        // The second request is still inside the window, so the user is full again.
        assertThatThrownBy(() -> limiter.acquire(QUESTIONS, "a@example.org")).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void rejectedRequestsDoNotUseUpTheAllowance() {
        RateLimiter limiter = limiter(true, 1, Duration.ofMinutes(1));
        limiter.acquire(QUESTIONS, "a@example.org");
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofSeconds(10));
            assertThatThrownBy(() -> limiter.acquire(QUESTIONS, "a@example.org")).isInstanceOf(RateLimitExceededException.class);
        }
        clock.advance(Duration.ofSeconds(10));
        assertThatCode(() -> limiter.acquire(QUESTIONS, "a@example.org")).doesNotThrowAnyException();
    }

    @Test
    void eachUserHasTheirOwnAllowance() {
        RateLimiter limiter = limiter(true, 1, Duration.ofMinutes(1));
        limiter.acquire(QUESTIONS, "a@example.org");
        assertThatCode(() -> limiter.acquire(QUESTIONS, "b@example.org")).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.acquire(QUESTIONS, "a@example.org")).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void canBeSwitchedOff() {
        RateLimiter limiter = limiter(false, 1, Duration.ofMinutes(1));
        for (int i = 0; i < 20; i++) {
            limiter.acquire(QUESTIONS, "a@example.org");
        }
    }

    @Test
    void retryAfterRoundsUpToWholeSecondsAndIsNeverZero() {
        assertThat(new RateLimitExceededException(Duration.ofMillis(1500)).getRetryAfterSeconds()).isEqualTo(2);
        assertThat(new RateLimitExceededException(Duration.ZERO).getRetryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void eachKindOfRequestHasItsOwnAllowanceAndMessage() {
        RateLimiter limiter = new RateLimiter(new RateLimitProperties(
                new RateLimitProperties.Limit(true, 1, Duration.ofMinutes(1)),
                new RateLimitProperties.Limit(true, 2, Duration.ofHours(1)),
                new RateLimitProperties.Limit(false, 1, Duration.ofMinutes(1))), clock);
        limiter.acquire(QUESTIONS, "a@example.org");
        assertThatThrownBy(() -> limiter.acquire(QUESTIONS, "a@example.org"))
                .hasMessageContaining("asking questions too quickly");

        // Questions being full does not stop assessments, which have their own window.
        limiter.acquire(RECOMMENDATIONS, "a@example.org");
        limiter.acquire(RECOMMENDATIONS, "a@example.org");
        assertThatThrownBy(() -> limiter.acquire(RECOMMENDATIONS, "a@example.org"))
                .isInstanceOfSatisfying(RateLimitExceededException.class, exception -> {
                    assertThat(exception.getMessage()).contains("requesting assessments too quickly");
                    assertThat(exception.getRetryAfter()).isEqualTo(Duration.ofHours(1));
                });

        // A switched-off allowance never limits.
        for (int i = 0; i < 5; i++) {
            limiter.acquire(EVALUATIONS, "a@example.org");
        }
    }
}
