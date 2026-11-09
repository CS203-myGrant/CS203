package mygrant.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class ProcessingForecastCalculatorTest {
    private static final LocalDate FILED = LocalDate.of(2026, 1, 1);

    @Test
    void stableSeriesUsesPublishedMedianOnlyWhenItExists() {
        var result = ProcessingForecastCalculator.calculate(FILED, series(120, 120, 120, 120, 120));
        assertThat(result.medianDecisionDate()).isNull();
        assertThat(result.upperBoundDecisionDate()).isEqualTo(FILED.plusDays(127));
        assertThat(result.uncertaintyDays()).isEqualTo(7);
        assertThat(result.uncertaintyExplanation()).contains("not an 80% forecast confidence");
        var withMedians = List.of(snapshot(1, 120, 80), snapshot(2, 120, 82));
        assertThat(ProcessingForecastCalculator.calculate(FILED, withMedians).medianDecisionDate())
                .isEqualTo(FILED.plusDays(81));
    }

    @Test
    void risingVolatileAndSparseSeriesWidenUncertainty() {
        int stable = ProcessingForecastCalculator.calculate(FILED, series(120, 120, 120, 120, 120))
                .uncertaintyDays();
        var rising = ProcessingForecastCalculator.calculate(FILED, series(100, 110, 120, 130, 140));
        var volatileSeries = ProcessingForecastCalculator.calculate(FILED, series(90, 160, 105, 180, 120));
        var sparse = ProcessingForecastCalculator.calculate(FILED, series(120, 120));
        assertThat(rising.uncertaintyDays()).isGreaterThan(stable);
        assertThat(volatileSeries.uncertaintyDays()).isGreaterThan(stable);
        assertThat(sparse.uncertaintyDays()).isGreaterThan(stable);
        assertThat(rising.uncertaintyExplanation()).contains("upward trend");
        assertThat(sparse.uncertaintyExplanation()).contains("few observations");
    }

    @Test
    void requiresAtLeastTwoSnapshotsAndFilingDate() {
        assertThatThrownBy(() -> ProcessingForecastCalculator.calculate(FILED, series(120)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProcessingForecastCalculator.calculate(null, series(120, 120)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<ProcessingForecastCalculator.Snapshot> series(int... values) {
        return java.util.stream.IntStream.range(0, values.length)
                .mapToObj(i -> snapshot(i + 1, values[i], null)).toList();
    }

    private static ProcessingForecastCalculator.Snapshot snapshot(int day, int p80, Integer median) {
        return new ProcessingForecastCalculator.Snapshot(LocalDate.of(2025, 12, day),
                BigDecimal.valueOf(p80), median == null ? null : BigDecimal.valueOf(median), "DAYS");
    }
}
