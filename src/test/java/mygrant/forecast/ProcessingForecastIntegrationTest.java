package mygrant.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import mygrant.user.User;
import mygrant.user.UserProfile;
import mygrant.user.UserProfileRepository;
import mygrant.user.UserRepository;

@SpringBootTest
@Transactional
class ProcessingForecastIntegrationTest {
    @Autowired ProcessingForecastService forecasts;
    @Autowired UserRepository users;
    @Autowired UserProfileRepository profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired ForecastRiskService risks;

    @Test
    void isolatesAccountsAndNeverMixesOfficesOrInventsMedian() {
        User first = applicant(LocalDate.of(2026, 1, 1));
        User second = applicant(LocalDate.of(2026, 2, 1));
        assertThat(forecasts.forecast(first.getEmail(), "I-765", null, null).reason())
                .isEqualTo("NO_USABLE_HISTORY");
        snapshot("I-765", "OPT", "NBC", LocalDate.of(2025, 12, 1), 120, null);
        snapshot("I-765", "OPT", "NBC", LocalDate.of(2025, 12, 8), 120, null);
        var firstResult = forecasts.forecast(first.getEmail(), "I-765", null, null);
        var secondResult = forecasts.forecast(second.getEmail(), "I-765", null, null);
        assertThat(firstResult.status()).isEqualTo("AVAILABLE");
        assertThat(firstResult.medianDecisionDate()).isNull();
        assertThat(firstResult.publishedPercentile()).isEqualTo(80);
        assertThat(firstResult.sourceSnapshotDates()).containsExactly(
                LocalDate.of(2025, 12, 1), LocalDate.of(2025, 12, 8));
        assertThat(secondResult.upperBoundDecisionDate()).isEqualTo(firstResult.upperBoundDecisionDate().plusMonths(1));

        snapshot("I-765", "OPT", "Texas", LocalDate.of(2025, 12, 1), 400, null);
        assertThat(forecasts.forecast(first.getEmail(), "I-765", null, null).reason())
                .isEqualTo("AMBIGUOUS_SERIES");
        assertThat(forecasts.forecast(first.getEmail(), "I-765", "OPT", "NBC")
                .upperBoundDecisionDate()).isEqualTo(firstResult.upperBoundDecisionDate());
        forecasts.selectSeries(first.getEmail(), "I-765", "OPT", "NBC");
        assertThat(forecasts.forecast(first.getEmail(), "I-765", null, null).office()).isEqualTo("NBC");
        assertThat(forecasts.forecast(second.getEmail(), "I-765", null, null).reason())
                .isEqualTo("AMBIGUOUS_SERIES");
        assertThatThrownBy(() -> forecasts.selectSeries(first.getEmail(), "I-765", "STEM", "NBC"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingFilingDateUnsupportedFormAndSparseHistoryAreExplicit() {
        User user = applicant(null);
        assertThat(forecasts.forecast(user.getEmail(), "I-765", null, null).reason())
                .isEqualTo("MISSING_FILING_DATE");
        assertThat(forecasts.forecast(user.getEmail(), "I-999", null, null).reason())
                .isEqualTo("UNSUPPORTED_FORM");
        User withDate = applicant(LocalDate.of(2026, 1, 1));
        snapshot("I-765", "OPT", "NBC", LocalDate.of(2025, 12, 1), 120, null);
        assertThat(forecasts.forecast(withDate.getEmail(), "I-765", null, null).reason())
                .isEqualTo("INSUFFICIENT_HISTORY");
    }

    @Test
    void deadlineRiskIsOnlyAfterTheUpperDateAndRepeatedChecksKeepOneAlert() {
        User user = applicant(LocalDate.of(2026, 1, 1));
        snapshot("I-765", "OPT", "NBC", LocalDate.of(2025, 12, 1), 120, null);
        snapshot("I-765", "OPT", "NBC", LocalDate.of(2025, 12, 8), 120, null);
        UserProfile profile = profiles.findById(user.getId()).orElseThrow();
        LocalDate upper = forecasts.forecast(user.getEmail(), "I-765", null, null).upperBoundDecisionDate();
        profile.setOptStartDate(upper);
        assertThat(forecasts.forecast(user.getEmail(), "I-765", null, null).deadlineConflict()).isFalse();
        profile.setOptStartDate(upper.plusDays(1));
        assertThat(forecasts.forecast(user.getEmail(), "I-765", null, null).deadlineConflict()).isFalse();
        profile.setOptStartDate(upper.minusDays(1));
        var conflict = forecasts.forecast(user.getEmail(), "I-765", null, null);
        assertThat(conflict.deadlineConflict()).isTrue();
        risks.saveOrClear(user.getId(), conflict);
        risks.saveOrClear(user.getId(), conflict);
        assertThat(risks.alertsFor(user.getEmail())).hasSize(1)
                .first().satisfies(alert -> assertThat(alert.message()).contains("USCIS OPT at NBC"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM forecast_deadline_alerts WHERE user_id=?",
                Long.class, user.getId())).isEqualTo(1);
        profile.setOptStartDate(upper);
        risks.saveOrClear(user.getId(), forecasts.forecast(user.getEmail(), "I-765", null, null));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM forecast_deadline_alerts WHERE user_id=?",
                Long.class, user.getId())).isZero();
    }

    private User applicant(LocalDate filingDate) {
        User user = users.save(new User("Forecast test", "forecast-" + UUID.randomUUID() + "@example.org",
                "unused"));
        UserProfile profile = UserProfile.registrationProfile(user.getId(), "F-1", null, null, null, null);
        profile.setI765FilingDate(filingDate);
        profile.setOptStartDate(LocalDate.of(2026, 5, 1));
        profiles.save(profile);
        return user;
    }

    private void snapshot(String form, String category, String office, LocalDate date, int p80, Integer median) {
        jdbc.update("""
                INSERT INTO processing_time_snapshots
                (source_name, form_type, form_category, office, observed_on, percentile,
                 percentile_value, median_value, duration_unit, source_url,
                 source_content_hash, source_excerpt, imported_at)
                VALUES ('USCIS_PROCESSING_TIMES', ?, ?, ?, ?, 80, ?, ?, 'DAYS',
                        'https://egov.uscis.gov/processing-times/', ?, 'SYNTHETIC TEST DATA', now())
                """, form, category, office, date, p80, median,
                UUID.randomUUID().toString().replace("-", "").repeat(2));
    }
}
