package mygrant.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import mygrant.user.User;

class EmailEligibilityTests {

    private final EmailEligibility eligibility = new EmailEligibility(new EmailProperties("http://localhost:8080", false));

    @Test
    void workosAccountWithAnAddressCanReceiveEmail() {
        assertThat(eligibility.check(verifiedUser())).isEmpty();
        assertThat(eligibility.canReceive(verifiedUser())).isTrue();
    }

    @Test
    void passwordRegisteredAccountIsUnverified() {
        User user = verifiedUser();
        user.setWorkosUserId(null);

        assertThat(eligibility.check(user)).contains(IneligibleReason.EMAIL_NOT_VERIFIED);
    }

    @Test
    void demoFlagLetsUnverifiedAccountsReceiveEmail() {
        EmailEligibility demo = new EmailEligibility(new EmailProperties("http://localhost:8080", true));
        User user = verifiedUser();
        user.setWorkosUserId(null);

        assertThat(demo.check(user)).isEmpty();
    }

    @Test
    void demoFlagStillExcludesInactiveAndAddresslessAccounts() {
        EmailEligibility demo = new EmailEligibility(new EmailProperties("http://localhost:8080", true));
        User inactive = verifiedUser();
        inactive.setActive(false);
        User noEmail = verifiedUser();
        noEmail.setEmail(" ");

        assertThat(demo.check(inactive)).contains(IneligibleReason.ACCOUNT_INACTIVE);
        assertThat(demo.check(noEmail)).contains(IneligibleReason.NO_EMAIL_ADDRESS);
    }

    @Test
    void inactiveAndPurgedAccountsCannotReceiveEmail() {
        User inactive = verifiedUser();
        inactive.setActive(false);
        User purged = verifiedUser();
        purged.anonymizeAndDeactivate(Instant.now());

        assertThat(eligibility.check(inactive)).contains(IneligibleReason.ACCOUNT_INACTIVE);
        assertThat(eligibility.check(purged)).contains(IneligibleReason.ACCOUNT_INACTIVE);
        assertThat(eligibility.check(null)).contains(IneligibleReason.ACCOUNT_INACTIVE);
    }

    @Test
    void accountWithoutAnAddressCannotReceiveEmail() {
        User user = verifiedUser();
        user.setEmail(null);

        assertThat(eligibility.check(user)).contains(IneligibleReason.NO_EMAIL_ADDRESS);
    }

    private User verifiedUser() {
        User user = new User("Student", "student@example.com", null, "F-1", null, null);
        user.setWorkosUserId("user_workos_123");
        return user;
    }
}
