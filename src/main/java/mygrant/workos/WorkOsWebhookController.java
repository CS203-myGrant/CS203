package mygrant.workos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import mygrant.account.AccountPurgeService;
import mygrant.user.UserRepository;

@RestController
@RequestMapping("/api/v1/webhooks/workos")
public class WorkOsWebhookController {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkOsWebhookController.class);

    private final WorkOsWebhookVerifier verifier;
    private final WelcomeEmailService welcomeEmailService;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final AccountPurgeService accountPurgeService;

    public WorkOsWebhookController(
            WorkOsWebhookVerifier verifier,
            WelcomeEmailService welcomeEmailService,
            ObjectMapper objectMapper,
            UserRepository userRepository,
            AccountPurgeService accountPurgeService) {
        this.verifier = verifier;
        this.welcomeEmailService = welcomeEmailService;
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.accountPurgeService = accountPurgeService;
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
                try {
                    welcomeEmailService.sendWelcomeEmail(
                            user.path("email").asText(),
                            user.path("name").asText());
                } catch (RuntimeException emailFailure) {
                    // Email delivery must not make WorkOS retry an already accepted event.
                    LOGGER.warn("Unable to send welcome email for WorkOS user {}",
                            user.path("email").asText(), emailFailure);
                }
            } else if ("user.deleted".equals(event.path("event").asText())) {
                String workOsUserId = event.path("data").path("id").asText("");
                userRepository.findByWorkosUserId(workOsUserId)
                        .ifPresent(user -> accountPurgeService.purge(user.getId()));
            }
            return ResponseEntity.ok().build();
        } catch (Exception exception) {
            // A non-2xx response tells WorkOS to retry delivery.
            return ResponseEntity.internalServerError().build();
        }
    }
}
