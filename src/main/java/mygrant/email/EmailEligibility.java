package mygrant.email;

import java.util.Optional;

import org.springframework.stereotype.Component;

import mygrant.user.User;

/**
 * The one place that decides whether an account may receive alert emails. There is no
 * email-verified flag yet (CS203-25), so a linked WorkOS identity stands in for one:
 * WorkOS sign-in rejects unverified addresses.
 */
@Component
public class EmailEligibility {

    private final boolean allowUnverifiedRecipients;

    public EmailEligibility(EmailProperties properties) {
        this.allowUnverifiedRecipients = properties.allowUnverifiedRecipients();
    }

    /** Empty when the account can receive alert emails, otherwise the reason it cannot. */
    public Optional<IneligibleReason> check(User user) {
        if (user == null || !user.isActive() || user.getDeletedAt() != null) {
            return Optional.of(IneligibleReason.ACCOUNT_INACTIVE);
        }
        if (isBlank(user.getEmail())) {
            return Optional.of(IneligibleReason.NO_EMAIL_ADDRESS);
        }
        if (!allowUnverifiedRecipients && isBlank(user.getWorkosUserId())) {
            return Optional.of(IneligibleReason.EMAIL_NOT_VERIFIED);
        }
        return Optional.empty();
    }

    public boolean canReceive(User user) {
        return check(user).isEmpty();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
