package mygrant.workos;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class WelcomeEmailService {

    private final RestClient client;
    private final String apiKey;
    private final String from;

    public WelcomeEmailService(
            @Value("${RESEND_API_KEY:}") String apiKey,
            @Value("${RESEND_FROM_EMAIL:}") String from) {
        this.client = RestClient.builder().baseUrl("https://api.resend.com").build();
        this.apiKey = apiKey;
        this.from = from;
    }

    public void sendWelcomeEmail(String email, String name) {
        if (apiKey.isBlank() || from.isBlank()) {
            throw new IllegalStateException("Resend email settings are not configured");
        }

        String greeting = name == null || name.isBlank() ? "there" : name;
        client.post()
                .uri("/emails")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .body(Map.of(
                        "from", from,
                        "to", new String[] {email},
                        "subject", "Welcome to myGRANT",
                        "html", "<p>Hi " + escapeHtml(greeting) + ",</p>"
                                + "<p>Your myGRANT account was created successfully.</p>"
                                + "<p>Complete your profile to start receiving policy updates.</p>"))
                .retrieve()
                .toBodilessEntity();
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
