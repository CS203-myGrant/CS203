package mygrant.ai;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * CS203-174: timeouts and bounded retry for every call to an AI provider: OpenAI chat
 * ({@link OpenAiGenerationService}), the Anthropic judge and, through Spring AI, OpenAI embeddings.
 * Embeddings take their timeouts from {@code spring.http.client.*} instead, because Spring AI
 * builds that client from the application's shared RestClient.Builder.
 */
@Validated
@ConfigurationProperties("mygrant.ai")
public record AiResilienceProperties(@DefaultValue @Valid Http http, @DefaultValue @Valid Retry retry) {

    /** Chat completions answer only once generation finishes, so the read timeout is generous. */
    public record Http(
            @DefaultValue("5s") @NotNull Duration connectTimeout,
            @DefaultValue("90s") @NotNull Duration readTimeout) {}

    /**
     * Retries only timeouts and other I/O failures, 429 and 5xx. A Retry-After header is honoured,
     * unless it asks for longer than {@code maxRetryAfter}, in which case the call fails at once.
     */
    public record Retry(
            @DefaultValue("3") @Min(1) @Max(10) int maxAttempts,
            @DefaultValue("1s") @NotNull Duration initialBackoff,
            @DefaultValue("2.0") @DecimalMin("1.0") double multiplier,
            @DefaultValue("8s") @NotNull Duration maxBackoff,
            @DefaultValue("20s") @NotNull Duration maxRetryAfter) {

        /** A single attempt: for clients built by hand, such as in tests. */
        public static Retry once() {
            return new Retry(1, Duration.ZERO, 1.0, Duration.ZERO, Duration.ZERO);
        }
    }
}
