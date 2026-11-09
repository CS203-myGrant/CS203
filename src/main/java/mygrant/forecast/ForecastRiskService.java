package mygrant.forecast;

import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

/** Persists timing-risk alerts when profile or source data changes, never on a dashboard read. */
@Service
public class ForecastRiskService {
    private static final Logger LOG = LoggerFactory.getLogger(ForecastRiskService.class);
    private final ProcessingForecastService forecasts;
    private final UserRepository users;
    private final JdbcTemplate jdbc;

    public ForecastRiskService(ProcessingForecastService forecasts, UserRepository users, JdbcTemplate jdbc) {
        this.forecasts = forecasts;
        this.users = users;
        this.jdbc = jdbc;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void profileChanged(ProfileSaved event) {
        users.findById(event.userId()).ifPresent(user -> evaluate(user, "I-765", "I-539"));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void selectionSaved(SelectionSaved event) {
        users.findById(event.userId()).ifPresent(user -> evaluate(user, event.formType()));
    }

    @org.springframework.context.event.EventListener
    public void snapshotSaved(SnapshotSaved event) {
        for (User user : users.findByRole(UserRole.APPLICANT)) {
            if (user.isActive()) evaluate(user, event.formType());
        }
    }

    @Scheduled(cron = "0 15 2 * * *")
    public void dailyRefresh() {
        for (User user : users.findByRole(UserRole.APPLICANT)) {
            if (user.isActive()) evaluate(user, "I-765", "I-539");
        }
    }

    @Transactional(readOnly = true)
    public List<ForecastAlert> alertsFor(String email) {
        long userId = users.findByEmail(email).orElseThrow().getId();
        return jdbc.query("""
                SELECT id, form_type, deadline_type, deadline_date, upper_bound_date,
                       source_observed_on, message
                FROM forecast_deadline_alerts WHERE user_id=? ORDER BY deadline_date, id
                """, (rs, row) -> new ForecastAlert(rs.getLong("id"), rs.getString("form_type"),
                rs.getString("deadline_type"), rs.getObject("deadline_date", LocalDate.class),
                rs.getObject("upper_bound_date", LocalDate.class),
                rs.getObject("source_observed_on", LocalDate.class), rs.getString("message")), userId);
    }

    private void evaluate(User user, String... forms) {
        for (String form : forms) {
            if (!form.equals("I-765") && !form.equals("I-539")) continue;
            try {
                ProcessingForecastService.Forecast forecast = forecasts.forecast(user.getEmail(), form, null, null);
                saveOrClear(user.getId(), forecast);
            } catch (RuntimeException exception) {
                LOG.warn("Forecast risk evaluation failed for user {} and {}: {}",
                        user.getId(), form, exception.getClass().getSimpleName());
            }
        }
    }

    void saveOrClear(long userId, ProcessingForecastService.Forecast forecast) {
        if (!forecast.deadlineConflict()) {
            jdbc.update("DELETE FROM forecast_deadline_alerts WHERE user_id=? AND form_type=?",
                    userId, forecast.formType());
            return;
        }
        LocalDate latest = forecast.sourceSnapshotDates().getLast();
        jdbc.update("""
                INSERT INTO forecast_deadline_alerts
                  (user_id, form_type, deadline_type, filing_date, deadline_date,
                   upper_bound_date, source_observed_on, uncertainty_explanation, message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, form_type, deadline_type) DO UPDATE SET
                  filing_date=EXCLUDED.filing_date, deadline_date=EXCLUDED.deadline_date,
                  upper_bound_date=EXCLUDED.upper_bound_date,
                  source_observed_on=EXCLUDED.source_observed_on,
                  uncertainty_explanation=EXCLUDED.uncertainty_explanation,
                  message=EXCLUDED.message, updated_at=CURRENT_TIMESTAMP
                WHERE (forecast_deadline_alerts.filing_date, forecast_deadline_alerts.deadline_date,
                       forecast_deadline_alerts.upper_bound_date, forecast_deadline_alerts.source_observed_on,
                       forecast_deadline_alerts.uncertainty_explanation, forecast_deadline_alerts.message)
                      IS DISTINCT FROM
                      (EXCLUDED.filing_date, EXCLUDED.deadline_date, EXCLUDED.upper_bound_date,
                       EXCLUDED.source_observed_on, EXCLUDED.uncertainty_explanation, EXCLUDED.message)
                """, userId, forecast.formType(), forecast.deadlineType(), forecast.filingDate(),
                forecast.deadlineDate(), forecast.upperBoundDecisionDate(), latest,
                forecast.uncertaintyExplanation(), forecast.deadlineWarning()
                        + " Based on USCIS " + forecast.formCategory() + " at " + forecast.office()
                        + ", latest snapshot " + latest + ". " + forecast.uncertaintyExplanation());
    }

    public record ProfileSaved(long userId) {}
    public record SnapshotSaved(String formType) {}
    public record SelectionSaved(long userId, String formType) {}
    public record ForecastAlert(long id, String formType, String deadlineType,
            LocalDate deadlineDate, LocalDate upperBoundDecisionDate,
            LocalDate sourceSnapshotDate, String message) {}
}
