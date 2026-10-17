package mygrant.workos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks/workos")
public class WorkOsWebhookController {

    private final WorkOsWebhookVerifier verifier;
    private final WelcomeEmailService welcomeEmailService;
    private final ObjectMapper objectMapper;

    public WorkOsWebhookController(
            WorkOsWebhookVerifier verifier,
            WelcomeEmailService welcomeEmailService,
            ObjectMapper objectMapper) {
        this.verifier = verifier;
        this.welcomeEmailService = welcomeEmailService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestHeader(value = "WorkOS-Signature", required = false) String signature,
            @RequestBody String payload) {
        if (!verifier.isValid(payload, signature)) {
            return ResponseEntity.badRequest().build();
        }

        try {
            JsonNode event = objectMapper.readTree(payload);
            if ("user.created".equals(event.path("event").asText())) {
                JsonNode user = event.path("data");
                welcomeEmailService.sendWelcomeEmail(
                        user.path("email").asText(),
                        user.path("name").asText());
            }
            return ResponseEntity.ok().build();
        } catch (Exception exception) {
            // A non-2xx response tells WorkOS to retry delivery.
            return ResponseEntity.internalServerError().build();
        }
    }
}
