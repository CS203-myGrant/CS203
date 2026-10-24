package mygrant.email;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings under {@code mygrant.email}; application.yaml maps each one to an env var. */
@ConfigurationProperties(prefix = "mygrant.email")
public record EmailProperties(
        /** Public address of the app, used to build links in emails. No trailing slash needed. */
        @DefaultValue("http://localhost:8080") String appBaseUrl,
        /** Lets password-registered (unverified) accounts receive alerts. Local demos only. */
        @DefaultValue("false") boolean allowUnverifiedRecipients,
        /** Attempts per email, counting the first, before it is marked FAILED. */
        @DefaultValue("4") int maxAttempts,
        /** Wait before retry n is entry n-1; the last entry repeats if there are more attempts. */
        @DefaultValue({"1m", "5m", "15m"}) List<Duration> retryBackoff,
        /** Only alerts created this recently are emailed immediately; older ones stay in-app. */
        @DefaultValue("24h") Duration immediateLookback,
        /** A send claimed longer ago than this is assumed to have died with its process. */
        @DefaultValue("10m") Duration stuckAfter,
        /** Most emails one dispatcher run sends. */
        @DefaultValue("50") int batchSize,
        /** Zone for the times shown in emails and for the digest's local date. */
        @DefaultValue("America/New_York") ZoneId digestZone
) {
    public EmailProperties {
        appBaseUrl = appBaseUrl.endsWith("/") ? appBaseUrl.substring(0, appBaseUrl.length() - 1) : appBaseUrl;
        retryBackoff = List.copyOf(retryBackoff);
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("mygrant.email.max-attempts must be at least 1");
        }
        if (retryBackoff.isEmpty()) {
            throw new IllegalArgumentException("mygrant.email.retry-backoff needs at least one duration");
        }
        if (stuckAfter.compareTo(ResendEmailSender.CONNECT_TIMEOUT.plus(ResendEmailSender.READ_TIMEOUT)
                .multipliedBy(2)) < 0) {
            throw new IllegalArgumentException("mygrant.email.stuck-after must be well above the send timeout");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("mygrant.email.batch-size must be at least 1");
        }
    }

    /** Opens the email settings on the profile page after sign-in. */
    public String settingsUrl() {
        return appBaseUrl + "/mygrant-prototype.html#view=profile";
    }

    public String notificationUrl(Long notificationId) {
        return appBaseUrl + "/mygrant-prototype.html#notification=" + notificationId;
    }

    public String unsubscribeUrl(String token) {
        return appBaseUrl + "/api/v1/email/unsubscribe?token=" + token;
    }

    /** How long to wait after the given failed attempt (1-based) before the next one. */
    public Duration backoffAfter(int attempt) {
        return retryBackoff.get(Math.min(Math.max(attempt, 1), retryBackoff.size()) - 1);
    }
}
