package mygrant.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Deterministic projection from comparable USCIS snapshots; not a case-level probability model. */
public final class ProcessingForecastCalculator {
    public static final int MIN_OBSERVATIONS = 2;
    private static final double DAYS_PER_MONTH = 30.44;

    private ProcessingForecastCalculator() {}

    public record Snapshot(LocalDate observedOn, BigDecimal p80, BigDecimal median, String unit) {}
    public record Projection(LocalDate rangeStartDate, LocalDate medianDecisionDate,
            LocalDate upperBoundDecisionDate, int uncertaintyDays, List<LocalDate> snapshotDates,
            String uncertaintyExplanation) {}

    public static Projection calculate(LocalDate filingDate, List<Snapshot> observations) {
        if (filingDate == null || observations == null || observations.size() < MIN_OBSERVATIONS) {
            throw new IllegalArgumentException("A filing date and two comparable snapshots are required");
        }
        List<Snapshot> ordered = observations.stream().sorted(Comparator.comparing(Snapshot::observedOn)).toList();
        List<Double> p80 = ordered.stream().map(s -> days(s.p80(), s.unit())).toList();
        List<Double> medians = ordered.stream().filter(s -> s.median() != null)
                .map(s -> days(s.median(), s.unit())).toList();
        double centralP80 = median(p80);
        double mad = median(p80.stream().map(value -> Math.abs(value - centralP80)).toList());
        double upwardTrend = Math.max(0, p80.getLast() - p80.getFirst());
        int sparsePenalty = Math.max(0, 5 - ordered.size()) * 7;
        int margin = 7 + (int) Math.ceil(2 * mad + upwardTrend / 2) + sparsePenalty;
        LocalDate medianDate = medians.size() >= MIN_OBSERVATIONS
                ? filingDate.plusDays(Math.round(median(medians))) : null;
        double lowerBasis = medianDate == null ? centralP80 : median(medians);
        LocalDate lowerDate = filingDate.plusDays(Math.max(1, Math.round(lowerBasis - margin)));
        LocalDate upperDate = filingDate.plusDays(Math.round(centralP80 + margin));
        List<String> reasons = new ArrayList<>();
        reasons.add("Published USCIS 80th-percentile times are not an 80% forecast confidence level");
        if (mad > 0) reasons.add("snapshot volatility widened the range");
        if (upwardTrend > 0) reasons.add("an upward trend widened the range");
        if (sparsePenalty > 0) reasons.add("few observations widened the range");
        if (medianDate == null) reasons.add("USCIS did not provide a case median in at least two snapshots");
        return new Projection(lowerDate, medianDate, upperDate, margin,
                ordered.stream().map(Snapshot::observedOn).toList(), String.join("; ", reasons) + ".");
    }

    private static double days(BigDecimal value, String unit) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException("Invalid duration");
        return value.doubleValue() * ("MONTHS".equals(unit) ? DAYS_PER_MONTH : 1);
    }

    private static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }
}
