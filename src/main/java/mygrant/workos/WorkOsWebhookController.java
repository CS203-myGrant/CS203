package mygrant.workos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import mygrant.account.AccountPurgeService;
import mygrant.common.ApiError;
import mygrant.user.User;
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
    public ResponseEntity<ApiError> receive(
            @RequestHeader(value = "WorkOS-Signature", required = false) String signature,
            HttpServletRequest request) throws IOException {
        String payload = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!verifier.isValid(payload, signature)) {
            return ResponseEntity.badRequest()
                    .body(ApiError.of(HttpStatus.BAD_REQUEST, "Webhook signature is missing or invalid"));
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
                JsonNode deletedUser = event.path("data");
                String workOsUserId = deletedUser.path("id").asText("");
                String email = deletedUser.path("email").asText("");
                User localUser = userRepository.findByWorkosUserId(workOsUserId)
                        .orElseGet(() -> email.isBlank()
                                ? null
                                : userRepository.findByEmail(email).orElse(null));
                if (localUser != null) {
                    accountPurgeService.purge(localUser.getId());
                    LOGGER.info("Purged local account for deleted WorkOS user {}", workOsUserId);
                } else {
                    LOGGER.warn("No local account matched deleted WorkOS user {} ({})",
                            workOsUserId, email);
                }
            }
            return ResponseEntity.ok().build();
        } catch (Exception exception) {
            // A non-2xx response tells WorkOS to retry delivery.
            return ResponseEntity.internalServerError()
                    .body(ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR, "Webhook could not be processed"));
        }
    }
}
