package mygrant.email;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mygrant.user.User;
import mygrant.user.UserRepository;

/** Issues single-use email verification links for password accounts. */
@Service
public class EmailVerificationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(24);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final EmailSender sender;
    private final EmailProperties properties;

    public EmailVerificationService(UserRepository users, EmailSender sender, EmailProperties properties) {
        this.users = users;
        this.sender = sender;
        this.properties = properties;
    }

    @Transactional
    public void issueFor(String email) {
        User user = users.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new IllegalArgumentException("Account was not found"));
        if (user.isEmailVerified()) {
            return;
        }

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        user.setEmailVerificationTokenHash(hash(token));
        user.setEmailVerificationExpiresAt(Instant.now().plus(TOKEN_LIFETIME));
        users.save(user);

        if (!sender.isConfigured()) {
            LOGGER.warn("Email verification link was not sent because email delivery is not configured");
            return;
        }
        String name = user.getFullName() == null || user.getFullName().isBlank()
                ? "there" : escape(user.getFullName());
        String url = properties.verificationUrl(token);
        try {
            sender.send(new EmailMessage(user.getEmail(), "Verify your myGRANT email address",
                    "<p>Hi " + name + ",</p><p>Confirm your email address to receive myGRANT alerts:</p>"
                            + "<p><a href=\"" + escape(url) + "\">Verify email address</a></p>"
                            + "<p>This link expires in 24 hours.</p>",
                    "Hi " + user.getFullName() + ",\n\nVerify your email address: " + url
                            + "\n\nThis link expires in 24 hours.",
                    Map.of(), "email-verification:" + user.getId() + ":" + hash(token)));
        } catch (RuntimeException emailFailure) {
            // Registration has already created the account. Keep the token so the user can
            // request another message after the provider configuration is fixed.
            LOGGER.warn("Unable to send email verification message to {}", user.getEmail(), emailFailure);
        }
    }

    @Transactional
    public void confirm(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Email verification token is required");
        }
        User user = users.findByEmailVerificationTokenHash(hash(token.trim()))
                .orElseThrow(() -> new IllegalArgumentException("Email verification token is invalid or expired"));
        if (user.getEmailVerificationExpiresAt() == null
                || !user.getEmailVerificationExpiresAt().isAfter(Instant.now())) {
            throw new IllegalArgumentException("Email verification token is invalid or expired");
        }
        user.setEmailVerified(true);
        user.setEmailVerificationTokenHash(null);
        user.setEmailVerificationExpiresAt(null);
        users.save(user);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
