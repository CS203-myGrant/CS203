package mygrant.email;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings under {@code mygrant.email}; application.yaml maps each one to an env var. */
@ConfigurationProperties(prefix = "mygrant.email")
public record EmailProperties(
        /** Public address of the app, used to build links in emails. No trailing slash needed. */
        @DefaultValue("http://localhost:8080") String appBaseUrl,
        /** Lets password-registered (unverified) accounts receive alerts. Local demos only. */
        @DefaultValue("false") boolean allowUnverifiedRecipients
) {
    public EmailProperties {
        appBaseUrl = appBaseUrl.endsWith("/") ? appBaseUrl.substring(0, appBaseUrl.length() - 1) : appBaseUrl;
    }

    /** Opens the email settings on the profile page after sign-in. */
    public String settingsUrl() {
        return appBaseUrl + "/mygrant-prototype.html#view=profile";
    }

    public String unsubscribeUrl(String token) {
        return appBaseUrl + "/api/v1/email/unsubscribe?token=" + token;
    }
}
