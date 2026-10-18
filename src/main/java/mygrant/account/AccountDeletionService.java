package mygrant.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import mygrant.account.dto.DeletionRequestResponse;
import mygrant.account.dto.DeletionConfirmationResponse;
import mygrant.workos.WorkOsService;
import mygrant.user.User;
import mygrant.user.UserRepository;

/** Creates short-lived, single-use challenges for account deletion confirmation. */
@Service
public class AccountDeletionService {

    private static final Duration CHALLENGE_LIFETIME = Duration.ofMinutes(15);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final AccountDeletionRequestRepository deletionRequestRepository;
    private final AccountPurgeService accountPurgeService;
    private final WorkOsService workOsService;

    public AccountDeletionService(UserRepository userRepository,
            AccountDeletionRequestRepository deletionRequestRepository,
            AccountPurgeService accountPurgeService, WorkOsService workOsService) {
        this.userRepository = userRepository;
        this.deletionRequestRepository = deletionRequestRepository;
        this.accountPurgeService = accountPurgeService;
        this.workOsService = workOsService;
    }

    /** Creates a challenge without changing or deleting the account. */
    @Transactional
    public DeletionRequestResponse requestDeletion(String authenticatedEmail) {
        User user = userRepository.findByEmail(authenticatedEmail)
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated user no longer exists"));

        byte[] randomChallenge = new byte[32];
        SECURE_RANDOM.nextBytes(randomChallenge);
        String challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(randomChallenge);
        Instant expiresAt = Instant.now().plus(CHALLENGE_LIFETIME);

        deletionRequestRepository.deleteAllByUserIdAndConfirmedAtIsNull(user.getId());
        deletionRequestRepository.save(new AccountDeletionRequest(
                user.getId(), hashChallenge(challenge), expiresAt));

        return new DeletionRequestResponse(challenge, expiresAt);
    }

    /** Validates the user's one-time challenge, removes their WorkOS identity, and purges local data. */
    @Transactional
    public DeletionConfirmationResponse confirmDeletion(String authenticatedEmail, String challenge) {
        User user = userRepository.findByEmail(authenticatedEmail)
                .orElseThrow(() -> invalidChallenge());
        Instant now = Instant.now();
        AccountDeletionRequest deletionRequest = deletionRequestRepository
                .findByChallengeHashAndUserId(hashChallenge(challenge), user.getId())
                .filter(request -> request.isUsableAt(now))
                .orElseThrow(() -> invalidChallenge());

        deletionRequest.markConfirmed(now);
        deletionRequestRepository.save(deletionRequest);

        if (user.getWorkosUserId() != null) {
            workOsService.deleteUser(user.getWorkosUserId());
        }

        accountPurgeService.purge(user.getId());
        return new DeletionConfirmationResponse("deleted", now);
    }

    private String hashChallenge(String challenge) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(challenge.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private ResponseStatusException invalidChallenge() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Deletion confirmation is invalid or expired");
    }
}
