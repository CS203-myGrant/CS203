package mygrant.email;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sends email with the Resend REST API (POST /emails). Uses the same RESEND_API_KEY and
 * RESEND_FROM_EMAIL as the welcome email. Both the connect and read timeouts are short,
 * so a hung request ends well before the dispatcher treats a send as stuck.
 */
@Component
public class ResendEmailSender implements EmailSender {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    /** Resend's 409s that clear on their own; every other 4xx is a request it will always refuse. */
    private static final Set<String> RETRYABLE_CONFLICTS = Set.of("concurrent_idempotent_requests", "resource_locked");
    private static final Pattern ERROR_NAME = Pattern.compile("[a-z_]{1,60}");

    private final RestClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String apiKey;
    private final String from;

    @Autowired
    public ResendEmailSender(
            @Value("${RESEND_API_KEY:}") String apiKey,
            @Value("${RESEND_FROM_EMAIL:}") String from) {
        this(RestClient.builder()
                .baseUrl("https://api.resend.com")
                .requestFactory(requestFactory())
                .build(), apiKey, from);
    }

    ResendEmailSender(RestClient client, String apiKey, String from) {
        this.client = client;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.from = from == null ? "" : from;
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isBlank() && !from.isBlank();
    }

    @Override
    public String send(EmailMessage message) {
        if (!isConfigured()) {
            throw new EmailSendException(false, null, "Resend is not configured");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", from);
        body.put("to", List.of(message.to()));
        body.put("subject", message.subject());
        body.put("html", message.html());
        body.put("text", message.text());
        if (!message.headers().isEmpty()) {
            body.put("headers", message.headers());
        }
        try {
            JsonNode response = client.post()
                    .uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    // Resend returns the first response again, without sending, for a key it
                    // saw in the last 24 hours with the same payload.
                    .header("Idempotency-Key", message.idempotencyKey())
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return response == null ? null : response.path("id").asText(null);
        } catch (RestClientResponseException rejected) {
            throw classify(rejected);
        } catch (ResourceAccessException unreachable) {
            Throwable cause = unreachable.getCause() == null ? unreachable : unreachable.getCause();
            throw new EmailSendException(true, null, "Network error: " + cause.getClass().getSimpleName());
        } catch (RestClientException unreadable) {
            // The request may have been accepted; a retry with the same key is safe.
            throw new EmailSendException(true, null, "Unreadable response: " + unreadable.getClass().getSimpleName());
        }
    }

    private EmailSendException classify(RestClientResponseException rejected) {
        int status = rejected.getStatusCode().value();
        String errorName = errorName(rejected.getResponseBodyAsString());
        boolean retryable = status >= 500 || status == 429
                || (status == 409 && RETRYABLE_CONFLICTS.contains(errorName));
        return new EmailSendException(retryable, errorName,
                "HTTP " + status + (errorName == null ? "" : " " + errorName));
    }

    /** Only the machine-readable error name is kept; Resend's message can echo the recipient. */
    private String errorName(String responseBody) {
        try {
            String name = objectMapper.readTree(responseBody).path("name").asText("");
            return ERROR_NAME.matcher(name).matches() ? name : null;
        } catch (Exception notJson) {
            return null;
        }
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}
