package mygrant.ai;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;

import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.BackOffContext;
import org.springframework.retry.backoff.BackOffInterruptedException;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.context.RetryContextSupport;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

/**
 * CS203-174: applies {@link AiResilienceProperties} to every AI provider call.
 * <ul>
 * <li>OpenAI chat and the Anthropic judge get their own connect and read timeouts here, on top of
 * the application's RestClient.Builder, and retry through {@link AiCallRetry}.</li>
 * <li>OpenAI embeddings go through Spring AI, which builds its client from the shared builder, so
 * their timeouts are {@code spring.http.client.*}. Spring AI 1.0's own retry would try 10 times
 * with up to 3 minutes between attempts, never retry a timeout or a 429, and ignore Retry-After,
 * so this configuration replaces its {@code RetryTemplate} and {@code ResponseErrorHandler} (both
 * conditional on missing beans) with ones that follow the same rule as chat calls. That makes
 * the {@code spring.ai.retry.*} properties unused.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiResilienceProperties.class)
public class AiResilience {

    private static final int MAX_ERROR_BODY = 300;

    private final AiResilienceProperties properties;
    private final AiCallRetry.Sleeper sleeper;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public AiResilience(AiResilienceProperties properties) {
        this(properties, duration -> Thread.sleep(duration.toMillis()), Clock.systemUTC());
    }

    public AiResilience(AiResilienceProperties properties, AiCallRetry.Sleeper sleeper, Clock clock) {
        this.properties = properties;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    /** The builder with the AI connect and read timeouts. */
    public RestClient.Builder withTimeouts(RestClient.Builder builder) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(properties.http().connectTimeout())
                .withReadTimeout(properties.http().readTimeout());
        return builder.requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(settings));
    }

    public AiCallRetry retry() {
        return new AiCallRetry(properties.retry(), sleeper, clock);
    }

    /** Spring AI's retry for embeddings, following {@link AiCallRetry}. */
    @Bean
    public RetryTemplate aiRetryTemplate() {
        AiCallRetry retry = retry();
        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(new RetryPolicy() {
            @Override
            public boolean canRetry(RetryContext context) {
                Throwable last = context.getLastThrowable();
                return last == null || (context.getRetryCount() < retry.maxAttempts()
                        && retry.delayAfter(context.getRetryCount(), last).isPresent());
            }

            @Override
            public RetryContext open(RetryContext parent) {
                return new RetryContextSupport(parent);
            }

            @Override
            public void close(RetryContext context) {
            }

            @Override
            public void registerThrowable(RetryContext context, Throwable throwable) {
                ((RetryContextSupport) context).registerThrowable(throwable);
            }

            @Override
            public int getMaxAttempts() {
                return retry.maxAttempts();
            }
        });
        template.setBackOffPolicy(new BackOffPolicy() {
            @Override
            public BackOffContext start(RetryContext context) {
                return new Attempts(context);
            }

            @Override
            public void backOff(BackOffContext backOffContext) throws BackOffInterruptedException {
                RetryContext context = ((Attempts) backOffContext).context();
                Duration wait = retry.delayAfter(context.getRetryCount(), context.getLastThrowable())
                        .orElse(Duration.ZERO);
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new BackOffInterruptedException("Interrupted while waiting to retry an AI call", interrupted);
                }
            }
        });
        return template;
    }

    private record Attempts(RetryContext context) implements BackOffContext {}

    /**
     * Turns Spring AI provider errors into exceptions the retry rule understands: 429 and 5xx are
     * retryable and carry Retry-After; every other error is final. Bodies are cut short for the logs.
     */
    @Bean
    public ResponseErrorHandler aiResponseErrorHandler() {
        return new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return response.getStatusCode().isError();
            }

            @Override
            public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
                handleError(response);
            }

            @Override
            public void handleError(ClientHttpResponse response) throws IOException {
                int status = response.getStatusCode().value();
                String body = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
                if (body.length() > MAX_ERROR_BODY) body = body.substring(0, MAX_ERROR_BODY) + "...";
                String message = "HTTP " + status + " - " + (body.isBlank() ? "No response body available" : body);
                if (status == 429 || status >= 500) {
                    throw new RetryableAiException(message, status, AiCallRetry.parseRetryAfter(
                            response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER), clock).orElse(null));
                }
                throw new NonTransientAiException(message);
            }
        };
    }
}
