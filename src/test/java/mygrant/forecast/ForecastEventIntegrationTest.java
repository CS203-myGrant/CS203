package mygrant.forecast;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import mygrant.user.User;
import mygrant.user.UserProfile;
import mygrant.user.UserProfileRepository;
import mygrant.user.UserRepository;

@SpringBootTest
class ForecastEventIntegrationTest {
    @Autowired UserRepository users;
    @Autowired UserProfileRepository profiles;
    @Autowired ProcessingForecastService forecasts;
    @Autowired ForecastRiskService risks;
    @Autowired JdbcTemplate jdbc;

    @Test
    void savingAComparableSeriesCreatesOneOwnerOnlyTimingAlertAfterCommit() {
        String suffix = UUID.randomUUID().toString();
        String category = "SYNTHETIC-OPT-" + suffix;
        String office = "Synthetic office";
        User user = users.save(new User("Forecast event test", "forecast-event-" + suffix + "@example.org",
                "unused"));
        try {
            UserProfile profile = UserProfile.registrationProfile(user.getId(), "F-1", null, null, null, null);
            profile.setI765FilingDate(LocalDate.of(2026, 1, 1));
            profile.setOptStartDate(LocalDate.of(2026, 2, 1));
            profiles.save(profile);
            for (int day : new int[] {1, 8}) {
                jdbc.update("""
                        INSERT INTO processing_time_snapshots
                        (source_name, form_type, form_category, office, observed_on, percentile,
                         percentile_value, duration_unit, source_url, source_content_hash,
                         source_excerpt, imported_at)
                        VALUES ('USCIS_PROCESSING_TIMES','I-765',?,?,?,80,120,'DAYS',
                                'https://egov.uscis.gov/processing-times/',?,
                                'SYNTHETIC TEST DATA',now())
                        """, category, office, LocalDate.of(2025, 12, day),
                        UUID.randomUUID().toString().replace("-", "").repeat(2));
            }
            forecasts.selectSeries(user.getEmail(), "I-765", category, office);
            assertThat(risks.alertsFor(user.getEmail())).hasSize(1);
            forecasts.selectSeries(user.getEmail(), "I-765", category, office);
            assertThat(risks.alertsFor(user.getEmail())).hasSize(1);
        } finally {
            jdbc.update("DELETE FROM forecast_deadline_alerts WHERE user_id=?", user.getId());
            jdbc.update("DELETE FROM forecast_series_selections WHERE user_id=?", user.getId());
            jdbc.update("DELETE FROM user_profiles WHERE user_id=?", user.getId());
            jdbc.update("DELETE FROM users WHERE id=?", user.getId());
            jdbc.update("DELETE FROM processing_time_snapshots WHERE form_category=?", category);
        }
    }
}
