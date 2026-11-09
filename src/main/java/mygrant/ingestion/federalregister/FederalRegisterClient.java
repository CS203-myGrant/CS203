package mygrant.ingestion.federalregister;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.annotation.JsonProperty;

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

    // The search's own syntax: quoted phrases joined by | match any of them.
    private static final String SEARCH_TERM = "\"F-1\" | \"J-1\" | \"H-1B\" | \"nonimmigrant student\""
            + " | \"exchange visitor\" | \"specialty occupation\"";
    private static final List<String> SEARCH_AGENCIES = List.of(
            "homeland-security-department", "state-department", "labor-department");

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

    /**
     * Finds rules, proposed rules and notices from the agencies that govern F-1, J-1 and
     * H-1B status that were published in the date range and mention one of those
     * categories, newest first. A match is only a candidate: the classifier still decides
     * at import whether the document is about a supported visa type.
     */
    public List<String> searchDocumentNumbers(LocalDate from, LocalDate to, int limit) {
        Map<String, Object> variables = Map.of("term", SEARCH_TERM, "from", from.toString(),
                "to", to.toString(), "limit", limit);
        SearchResults results = withTransientRetry(() -> apiClient.get()
                .uri(uriBuilder -> uriBuilder.path("/documents.json")
                        .queryParam("conditions[term]", "{term}")
                        .queryParam("conditions[publication_date][gte]", "{from}")
                        .queryParam("conditions[publication_date][lte]", "{to}")
                        .queryParam("conditions[agencies][]", SEARCH_AGENCIES.toArray())
                        .queryParam("conditions[type][]", "RULE", "PRORULE", "NOTICE")
                        .queryParam("fields[]", "document_number")
                        .queryParam("order", "newest")
                        .queryParam("per_page", "{limit}")
                        .build(variables))
                .retrieve()
                .body(SearchResults.class));
        if (results == null || results.results() == null) {
            return List.of();
        }
        return results.results().stream().map(SearchResult::documentNumber).toList();
    }

    private record SearchResults(List<SearchResult> results) {
    }

    private record SearchResult(@JsonProperty("document_number") String documentNumber) {
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
