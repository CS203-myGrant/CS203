package mygrant.common;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * CS203-12: how many policy questions one user may ask per window. Every question costs an
 * embedding call, an answer generation and an independent evaluation, so it is capped per user.
 */
@Validated
@ConfigurationProperties("mygrant.questions.rate-limit")
public record QuestionRateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10") @Min(1) int maxRequests,
        @DefaultValue("1m") @NotNull Duration window) {}
