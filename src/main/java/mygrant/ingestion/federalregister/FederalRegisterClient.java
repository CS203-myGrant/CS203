package mygrant.ingestion.federalregister;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Retrieves Federal Register metadata and full text from the public API. */
@Component
public class FederalRegisterClient {

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_DELAY = Duration.ofMillis(300);

    private final RestClient apiClient;
    private final RestClient downloadClient;

    public FederalRegisterClient(RestClient.Builder builder) {
        this.apiClient = builder.clone()
                .baseUrl("https://www.federalregister.gov/api/v1")
                .build();
        this.downloadClient = builder.clone().build();
    }

    /** Fetches published metadata for a Federal Register document number. */
    public FederalRegisterDocument getDocument(String documentNumber) {
        return withTransientRetry(() -> apiClient.get()
                .uri("/documents/{documentNumber}.json", documentNumber)
                .retrieve()
                .body(FederalRegisterDocument.class));
    }

    /** Downloads source text and removes NUL characters PostgreSQL cannot store. */
    public String downloadFullText(String rawTextUrl) {
        if (!StringUtils.hasText(rawTextUrl)) {
            throw new IllegalArgumentException("Federal Register document has no raw text URL");
        }
        String content = withTransientRetry(() -> downloadClient.get().uri(rawTextUrl).retrieve().body(String.class));
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("Federal Register document returned empty full text");
        }
        // PostgreSQL TEXT cannot contain NUL bytes. Some Federal Register raw-text
        // documents include them, so remove only that invalid character.
        return content.replace("\u0000", "");
    }

    private <T> T withTransientRetry(Supplier<T> request) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return request.get();
            } catch (HttpServerErrorException | ResourceAccessException exception) {
                lastFailure = exception;
                if (attempt == MAX_ATTEMPTS) {
                    throw exception;
                }
                pauseBeforeRetry();
            }
        }
        throw lastFailure;
    }

    private void pauseBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying Federal Register request", exception);
        }
    }
}
