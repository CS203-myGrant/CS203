package mygrant.workos;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

class WorkOsWebhookVerifierTest {

    private static final String SECRET = "webhook-secret";
    private final WorkOsWebhookVerifier verifier = new WorkOsWebhookVerifier(SECRET);

    @Test
    void acceptsAValidRecentSignature() throws Exception {
        String payload = "{\"event\":\"user.created\"}";
        String timestamp = Long.toString(Instant.now().getEpochSecond());

        assertThat(verifier.isValid(payload, signature(payload, timestamp))).isTrue();
    }

    @Test
    void rejectsAChangedPayload() throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature = signature("original", timestamp);

        assertThat(verifier.isValid("changed", signature)).isFalse();
    }

    private String signature(String payload, String timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String hash = HexFormat.of().formatHex(mac.doFinal(
                (timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return "t=" + timestamp + ", v1=" + hash;
    }
}
