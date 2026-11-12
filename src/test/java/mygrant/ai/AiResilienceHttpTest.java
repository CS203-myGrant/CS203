package mygrant.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * CS203-174 against a local HTTP server standing in for the providers (never the real APIs): the
 * chat client's read timeout really fires and is retried, and Spring AI's OpenAI embedding client,
 * built from the application's RestClient.Builder, takes its timeout from spring.http.client.* and
 * uses this application's retry rule instead of Spring AI's default one.
 */
class AiResilienceHttpTest {

    /** What the stub answers to each request, in order; the last answer repeats. */
    private interface Answer {
        void respond(HttpExchange exchange) throws IOException, InterruptedException;
    }

    private HttpServer server;
    private final List<Answer> answers = new ArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final ConcurrentLinkedQueue<Duration> waits = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            int index = requests.getAndIncrement();
            try {
                answers.get(Math.min(index, answers.size() - 1)).respond(exchange);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static Answer status(int code, String retryAfter) {
        return exchange -> {
            if (retryAfter != null) exchange.getResponseHeaders().set("Retry-After", retryAfter);
            byte[] body = "{\"error\":{\"message\":\"stub\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, body.length);
            exchange.getResponseBody().write(body);
        };
    }

    private static Answer json(String body) {
        return exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        };
    }

    private static Answer slow(Duration delay) {
        return exchange -> {
            Thread.sleep(delay.toMillis());
            json("{}").respond(exchange);
        };
    }

    private AiResilience resilience(Duration readTimeout) {
        return new AiResilience(new AiResilienceProperties(
                new AiResilienceProperties.Http(Duration.ofSeconds(2), readTimeout),
                new AiResilienceProperties.Retry(3, Duration.ofMillis(10), 2.0, Duration.ofMillis(50),
                        Duration.ofSeconds(2))), waits::add, Clock.systemUTC());
    }

    private OpenAiGenerationService chat(AiResilience resilience) {
        return new OpenAiGenerationService(RestClient.builder(), resilience, "test-key", "test-model",
                baseUrl() + "/v1");
    }

    @Test
    void chatReadTimeoutFiresAndIsRetriedThenFails() {
        answers.add(slow(Duration.ofSeconds(2)));
        long started = System.nanoTime();

        assertThatThrownBy(() -> chat(resilience(Duration.ofMillis(200))).generate("system", "user"))
                .isInstanceOf(ResourceAccessException.class);

        assertThat(requests).hasValue(3);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void chatRetriesServerErrorsAndRateLimitsThenSucceeds() {
        answers.add(status(503, null));
        answers.add(status(429, "1"));
        answers.add(json("{\"choices\":[{\"message\":{\"content\":\"answer\"}}]}"));

        assertThat(chat(resilience(Duration.ofSeconds(5))).generate("system", "user")).isEqualTo("answer");
        assertThat(requests).hasValue(3);
        assertThat(waits).containsExactly(Duration.ofMillis(10), Duration.ofSeconds(1));
    }

    @Test
    void chatDoesNotRetryABadRequest() {
        answers.add(status(400, null));

        assertThatThrownBy(() -> chat(resilience(Duration.ofSeconds(5))).generate("system", "user"))
                .isInstanceOf(org.springframework.web.client.HttpClientErrorException.BadRequest.class);
        assertThat(requests).hasValue(1);
    }

    private ApplicationContextRunner embeddings(String readTimeout) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
                        RestClientAutoConfiguration.class, SpringAiRetryAutoConfiguration.class,
                        OpenAiEmbeddingAutoConfiguration.class))
                .withBean(AiResilience.class, () -> resilience(Duration.ofSeconds(90)))
                .withPropertyValues("spring.ai.openai.api-key=test-key", "spring.ai.openai.base-url=" + baseUrl(),
                        "spring.http.client.factory=jdk", "spring.http.client.read-timeout=" + readTimeout);
    }

    @Test
    void embeddingsUseThisRetryRuleIncludingRetryAfter() {
        answers.add(status(429, "1"));
        answers.add(status(500, null));
        answers.add(json("""
                {"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.25,0.5]}],
                 "model":"text-embedding-3-small","usage":{"prompt_tokens":1,"total_tokens":1}}"""));

        embeddings("5s").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(EmbeddingModel.class).embed("hello")).containsExactly(0.25f, 0.5f);
        });
        assertThat(requests).hasValue(3);
        assertThat(waits).containsExactly(Duration.ofSeconds(1), Duration.ofMillis(20));
    }

    @Test
    void embeddingsTakeTheSharedClientTimeoutAndRetryIt() {
        answers.add(slow(Duration.ofSeconds(2)));

        embeddings("200ms").run(context -> assertThatThrownBy(
                () -> context.getBean(EmbeddingModel.class).embed("hello"))
                .isInstanceOf(ResourceAccessException.class));
        assertThat(requests).hasValue(3);
    }

    @Test
    void embeddingsDoNotRetryAClientError() {
        answers.add(status(401, null));

        embeddings("5s").run(context -> assertThatThrownBy(
                () -> context.getBean(EmbeddingModel.class).embed("hello"))
                .isInstanceOf(NonTransientAiException.class).hasMessageContaining("HTTP 401"));
        assertThat(requests).hasValue(1);
    }
}
