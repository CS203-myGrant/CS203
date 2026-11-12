package mygrant.common;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How many AI-backed requests one user may make per window, separately for each kind of request
 * (CS203-12, CS203-176). Every such request costs model calls, so each is capped per user.
 */
@Validated
@ConfigurationProperties("mygrant.rate-limits")
public record RateLimitProperties(
        @DefaultValue @Valid Limit questions,
        @DefaultValue @Valid Limit recommendations,
        @DefaultValue @Valid Limit evaluations) {

    public record Limit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("10") @Min(1) int maxRequests,
            @DefaultValue("1m") @NotNull Duration window) {}

    public Limit limit(RateLimiter.Allowance allowance) {
        return switch (allowance) {
            case QUESTIONS -> questions;
            case RECOMMENDATIONS -> recommendations;
            case EVALUATIONS -> evaluations;
        };
    }
}
