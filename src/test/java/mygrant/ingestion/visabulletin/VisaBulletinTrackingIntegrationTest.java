package mygrant.ingestion.visabulletin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import mygrant.user.ProfileFieldConsent;
import mygrant.user.ProfileFieldConsentRepository;
import mygrant.user.ProfileFieldDefinition;
import mygrant.user.User;
import mygrant.user.UserProfile;
import mygrant.user.UserProfileRepository;
import mygrant.user.UserRepository;

/** Saved-response integration test: no State Department or AI network calls. */
@SpringBootTest
@Transactional
class VisaBulletinTrackingIntegrationTest {
    @Autowired VisaBulletinTrackingService tracking;
    @Autowired VisaBulletinImporter importer;
    @Autowired UserRepository users;
    @Autowired UserProfileRepository profiles;
    @Autowired ProfileFieldConsentRepository consents;

    @Test
    void consentedApplicantGetsDeduplicatedMonthlyAlertsOnlyAfterAChange() throws IOException {
        User subscriber = applicant("bulletin-subscriber@example.test", true);
        User other = applicant("bulletin-other@example.test", false);
        consents.saveAndFlush(new ProfileFieldConsent(subscriber.getId(),
                ProfileFieldDefinition.VISA_TYPE, Instant.now()));
        assertThat(tracking.view(other.getEmail()).enabled()).isFalse();
        assertThat(tracking.setTracking(subscriber.getEmail(), true).enabled()).isTrue();

        importer.importSaved(saved("september-2026.html"));
        assertThat(tracking.view(subscriber.getEmail()).alerts()).isEmpty(); // baseline
        importer.importSaved(saved("october-2026.html"));
        var october = tracking.view(subscriber.getEmail());
        assertThat(october.latestMonth()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(october.currentCutoffs()).hasSize(2);
        assertThat(october.alerts()).isNotEmpty();
        assertThat(october.alerts()).allSatisfy(alert -> {
            assertThat(alert.publicationMonth()).isEqualTo(YearMonth.of(2026, 10));
            assertThat(alert.message()).contains("priority date", "does not establish filing eligibility");
            assertThat(alert.sourceUrl()).contains("travel.state.gov");
        });
        assertThat(tracking.view(other.getEmail()).alerts()).isEmpty();
        int count = october.alerts().size();
        importer.importSaved(saved("october-2026.html"));
        assertThat(tracking.view(subscriber.getEmail()).alerts()).hasSize(count);
        String unchangedOctober = saved("september-2026.html")
                .replace("Visa Bulletin For September 2026", "Visa Bulletin For October 2026");
        importer.importSaved(unchangedOctober);
        assertThat(tracking.view(subscriber.getEmail()).alerts()).isEmpty();

        tracking.setTracking(subscriber.getEmail(), false);
        assertThat(tracking.view(subscriber.getEmail()).enabled()).isFalse();
        assertThat(tracking.view(subscriber.getEmail()).alerts()).isEmpty();
    }

    @Test
    void missingConsentCannotEnableAndHistoricalBackfillDoesNotAlert() throws IOException {
        User subscriber = applicant("bulletin-no-consent@example.test", false);
        assertThatThrownBy(() -> tracking.setTracking(subscriber.getEmail(), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Consent");
        consentAll(subscriber.getId());
        importer.importSaved(saved("october-2026.html"));
        tracking.setTracking(subscriber.getEmail(), true);
        importer.importSaved(saved("september-2026.html"));
        assertThat(tracking.view(subscriber.getEmail()).latestMonth()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(tracking.view(subscriber.getEmail()).alerts()).isEmpty();
    }

    private User applicant(String email, boolean consent) {
        User user = new User();
        user.setFullName("Bulletin integration user");
        user.setEmail(email);
        user.setPasswordHash("integration-test-hash");
        user = users.saveAndFlush(user);
        UserProfile profile = UserProfile.registrationProfile(user.getId(), "F-1", null, null, null, null);
        profile.setImmigrantCategory("EB-2");
        profile.setChargeabilityArea("INDIA");
        profile.setPriorityDate(LocalDate.of(2014, 1, 1));
        profiles.saveAndFlush(profile);
        if (consent) consentAll(user.getId());
        return user;
    }

    private void consentAll(long userId) {
        consents.saveAll(List.of(
                new ProfileFieldConsent(userId, ProfileFieldDefinition.IMMIGRANT_CATEGORY, Instant.now()),
                new ProfileFieldConsent(userId, ProfileFieldDefinition.CHARGEABILITY_AREA, Instant.now()),
                new ProfileFieldConsent(userId, ProfileFieldDefinition.PRIORITY_DATE, Instant.now())));
    }

    private String saved(String name) throws IOException {
        try (var stream = getClass().getResourceAsStream("/visabulletin/" + name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
