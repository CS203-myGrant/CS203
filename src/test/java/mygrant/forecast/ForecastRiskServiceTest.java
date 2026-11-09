package mygrant.forecast;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import mygrant.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class ForecastRiskServiceTest {
    @Mock ProcessingForecastService forecasts;
    @Mock UserRepository users;
    @Mock JdbcTemplate jdbc;

    @Test
    void savesOnlyAfterDeadlineAndUsesStableUserFormDeadlineKey() {
        var service = new ForecastRiskService(forecasts, users, jdbc);
        service.saveOrClear(7, result(LocalDate.of(2026, 6, 1), false));
        service.saveOrClear(7, result(LocalDate.of(2026, 6, 2), true));
        service.saveOrClear(7, result(LocalDate.of(2026, 6, 2), true));
        verify(jdbc).update(eq("DELETE FROM forecast_deadline_alerts WHERE user_id=? AND form_type=?"),
                eq(7L), eq("I-765"));
        verify(jdbc, times(2)).update(org.mockito.ArgumentMatchers.contains(
                "ON CONFLICT (user_id, form_type, deadline_type)"),
                eq(7L), eq("I-765"), eq("OPT_START_DATE"), any(), any(), any(), any(), any(), any());
    }

    private ProcessingForecastService.Forecast result(LocalDate upper, boolean conflict) {
        LocalDate deadline = LocalDate.of(2026, 6, 1);
        return new ProcessingForecastService.Forecast("AVAILABLE", null, "I-765", "OPT", "National Benefits Center",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1), null, upper,
                "UNCALIBRATED_PROJECTION", 14, "Few observations widened the range.",
                List.of(LocalDate.of(2025, 12, 1)), 80, "test method", "OPT_START_DATE", deadline,
                conflict, conflict ? "Timing risk" : null, List.of());
    }
}
