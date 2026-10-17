package mygrant.workos;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WorkOsWebhookVerifier {

    private static final long TIMESTAMP_TOLERANCE_SECONDS = 300;
    private final String secret;

    public WorkOsWebhookVerifier(@Value("${WORKOS_WEBHOOK_SECRET:}") String secret) {
        this.secret = secret;
    }

    public boolean isValid(String payload, String signatureHeader) {
        if (secret.isBlank() || payload == null || signatureHeader == null) return false;

        String timestamp = null;
        String signature = null;
        for (String part : signatureHeader.split(",")) {
            part = part.trim();
            if (part.startsWith("t=")) timestamp = part.substring(2);
            if (part.startsWith("v1=")) signature = part.substring(3);
        }
        if (timestamp == null || signature == null) return false;

        try {
            long rawTimestamp = Long.parseLong(timestamp);
            long timestampSeconds = rawTimestamp > 10_000_000_000L
                    ? rawTimestamp / 1000
                    : rawTimestamp;
            if (Math.abs(Instant.now().getEpochSecond() - timestampSeconds)
                    > TIMESTAMP_TOLERANCE_SECONDS) return false;

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
            String expected = HexFormat.of().formatHex(digest);
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            return false;
        }
    }
}
