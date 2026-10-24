package mygrant.email;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Signs the unsubscribe links in alert emails. A token is {@code <userId>.<HMAC-SHA256>}
 * and only ever authorises turning that user's alert emails off; it is not a login token.
 * The key is derived from jwt.secret under its own label, so it cannot be confused with a
 * JWT signature. Tokens do not expire, as unsubscribe links in old emails must keep working;
 * rotating JWT_SECRET invalidates every link already sent.
 */
@Component
public class UnsubscribeTokens {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String KEY_LABEL = "mygrant/email-unsubscribe-key/v1";
    private static final String MESSAGE_PREFIX = "unsubscribe:v1:";
    private static final Pattern TOKEN = Pattern.compile("([1-9][0-9]{0,18})\\.([A-Za-z0-9_-]{43})");

    private final byte[] key;

    public UnsubscribeTokens(@Value("${jwt.secret}") String jwtSecret) {
        this.key = hmac(jwtSecret.getBytes(StandardCharsets.UTF_8), KEY_LABEL);
    }

    public String issue(Long userId) {
        return userId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature(userId.toString()));
    }

    /** The user the token was issued for, or empty for anything malformed or tampered with. */
    public Optional<Long> verify(String token) {
        if (token == null) {
            return Optional.empty();
        }
        var match = TOKEN.matcher(token);
        if (!match.matches()) {
            return Optional.empty();
        }
        byte[] presented = Base64.getUrlDecoder().decode(match.group(2));
        if (!MessageDigest.isEqual(presented, signature(match.group(1)))) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(match.group(1)));
        } catch (NumberFormatException tooLarge) {
            return Optional.empty();
        }
    }

    private byte[] signature(String userId) {
        return hmac(key, MESSAGE_PREFIX + userId);
    }

    private static byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }
}
