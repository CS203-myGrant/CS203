package mygrant.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class QuestionRateLimiterTest {

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-11T09:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration duration) { now = now.plus(duration); }
    }

    private final MutableClock clock = new MutableClock();

    private QuestionRateLimiter limiter(boolean enabled, int max, Duration window) {
        return new QuestionRateLimiter(new QuestionRateLimitProperties(enabled, max, window), clock);
    }

    @Test
    void allowsUpToTheLimitThenRejectsWithTimeUntilTheOldestExpires() {
        QuestionRateLimiter limiter = limiter(true, 3, Duration.ofMinutes(1));
        limiter.acquire("a@example.org");
        clock.advance(Duration.ofSeconds(10));
        limiter.acquire("a@example.org");
        limiter.acquire("a@example.org");

        assertThatThrownBy(() -> limiter.acquire("a@example.org"))
                .isInstanceOfSatisfying(RateLimitExceededException.class, exception -> {
                    assertThat(exception.getRetryAfter()).isEqualTo(Duration.ofSeconds(50));
                    assertThat(exception.getRetryAfterSeconds()).isEqualTo(50);
                });
    }

    @Test
    void slidingWindowFreesASlotWhenTheOldestRequestExpires() {
        QuestionRateLimiter limiter = limiter(true, 2, Duration.ofMinutes(1));
        limiter.acquire("a@example.org");
        clock.advance(Duration.ofSeconds(30));
        limiter.acquire("a@example.org");
        assertThatThrownBy(() -> limiter.acquire("a@example.org")).isInstanceOf(RateLimitExceededException.class);

        clock.advance(Duration.ofSeconds(30));
        assertThatCode(() -> limiter.acquire("a@example.org")).doesNotThrowAnyException();
        // The second request is still inside the window, so the user is full again.
        assertThatThrownBy(() -> limiter.acquire("a@example.org")).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void rejectedRequestsDoNotUseUpTheAllowance() {
        QuestionRateLimiter limiter = limiter(true, 1, Duration.ofMinutes(1));
        limiter.acquire("a@example.org");
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofSeconds(10));
            assertThatThrownBy(() -> limiter.acquire("a@example.org")).isInstanceOf(RateLimitExceededException.class);
        }
        clock.advance(Duration.ofSeconds(10));
        assertThatCode(() -> limiter.acquire("a@example.org")).doesNotThrowAnyException();
    }

    @Test
    void eachUserHasTheirOwnAllowance() {
        QuestionRateLimiter limiter = limiter(true, 1, Duration.ofMinutes(1));
        limiter.acquire("a@example.org");
        assertThatCode(() -> limiter.acquire("b@example.org")).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.acquire("a@example.org")).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void canBeSwitchedOff() {
        QuestionRateLimiter limiter = limiter(false, 1, Duration.ofMinutes(1));
        for (int i = 0; i < 20; i++) {
            limiter.acquire("a@example.org");
        }
    }

    @Test
    void retryAfterRoundsUpToWholeSecondsAndIsNeverZero() {
        assertThat(new RateLimitExceededException(Duration.ofMillis(1500)).getRetryAfterSeconds()).isEqualTo(2);
        assertThat(new RateLimitExceededException(Duration.ZERO).getRetryAfterSeconds()).isEqualTo(1);
    }
}
