package mygrant.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mygrant.user.UserProfile;
import mygrant.user.UserProfileRepository;
import mygrant.user.UserRepository;

@Service
public class ProcessingForecastService {
    private static final Set<String> FORMS = Set.of("I-765", "I-539", "I-140");
    private static final String METHOD = "Median of up to 12 comparable USCIS 80th-percentile snapshots; "
            + "range margin = 7 days + 2 × median absolute deviation + half the upward trend "
            + "+ 7 days for each observation below five. A case median is shown only when published.";

    private final UserRepository users;
    private final UserProfileRepository profiles;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    public ProcessingForecastService(UserRepository users, UserProfileRepository profiles,
            JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.users = users;
        this.profiles = profiles;
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Forecast forecast(String email, String rawFormType, String category, String office) {
        if (rawFormType == null || !rawFormType.matches("(?i)I-[0-9]{3}")) {
            throw new IllegalArgumentException("formType must look like I-765");
        }
        String formType = rawFormType.toUpperCase();
        long userId = users.findByEmail(email).orElseThrow().getId();
        if ((category == null || category.isBlank()) && (office == null || office.isBlank())) {
            List<Series> chosen = jdbc.query("""
                    SELECT form_category, office FROM forecast_series_selections
                    WHERE user_id=? AND form_type=?
                    """, (rs, row) -> new Series(rs.getString(1), rs.getString(2)), userId, formType);
            if (!chosen.isEmpty()) {
                category = chosen.getFirst().category();
                office = chosen.getFirst().office();
            }
        }
        UserProfile profile = profiles.findById(userId).orElse(null);
        if (!FORMS.contains(formType)) return Forecast.unavailable(formType, "UNSUPPORTED_FORM");
        LocalDate filingDate = filingDate(profile, formType);
        if (filingDate == null) return Forecast.unavailable(formType, "MISSING_FILING_DATE");
        if (filingDate.isAfter(LocalDate.now())) return Forecast.unavailable(formType, "FILING_DATE_IN_FUTURE");

        String sql = """
                SELECT DISTINCT form_category, office FROM processing_time_snapshots
                WHERE source_name='USCIS_PROCESSING_TIMES' AND form_type=? AND percentile=80
                  AND observed_on<=CURRENT_DATE
                """ + (category == null || category.isBlank() ? "" : " AND form_category=?")
                    + (office == null || office.isBlank() ? "" : " AND office=?");
        var args = new java.util.ArrayList<Object>();
        args.add(formType);
        if (category != null && !category.isBlank()) args.add(category.trim());
        if (office != null && !office.isBlank()) args.add(office.trim());
        List<Series> series = jdbc.query(sql, (rs, row) ->
                new Series(rs.getString("form_category"), rs.getString("office")), args.toArray());
        if (series.isEmpty()) return Forecast.unavailable(formType, "NO_USABLE_HISTORY");
        if (series.size() != 1) return Forecast.unavailable(formType, "AMBIGUOUS_SERIES", series);
        Series selected = series.getFirst();
        List<ProcessingForecastCalculator.Snapshot> snapshots = jdbc.query("""
                SELECT observed_on, percentile_value, median_value, duration_unit FROM (
                  SELECT DISTINCT ON (observed_on) observed_on, percentile_value, median_value,
                         duration_unit, imported_at, id
                  FROM processing_time_snapshots
                  WHERE source_name='USCIS_PROCESSING_TIMES' AND form_type=?
                    AND form_category=? AND office=? AND percentile=80 AND observed_on<=CURRENT_DATE
                  ORDER BY observed_on DESC, imported_at DESC, id DESC
                ) latest ORDER BY observed_on DESC LIMIT 12
                """, (rs, row) -> new ProcessingForecastCalculator.Snapshot(
                rs.getObject("observed_on", LocalDate.class),
                rs.getBigDecimal("percentile_value"), rs.getBigDecimal("median_value"),
                rs.getString("duration_unit")), formType, selected.category(), selected.office());
        if (snapshots.size() < ProcessingForecastCalculator.MIN_OBSERVATIONS) {
            return Forecast.unavailable(formType, "INSUFFICIENT_HISTORY");
        }
        var result = ProcessingForecastCalculator.calculate(filingDate, snapshots);
        String deadlineType = deadlineType(formType);
        LocalDate deadline = deadline(profile, formType);
        boolean conflict = deadline != null && result.upperBoundDecisionDate().isAfter(deadline);
        String warning = conflict ? "Projected upper decision-window date " + result.upperBoundDecisionDate()
                + " is after your " + deadlineType + " of " + deadline
                + ". Plan for a possible timing risk; individual cases can differ." : null;
        return new Forecast("AVAILABLE", null, formType, selected.category(), selected.office(),
                filingDate, result.rangeStartDate(), result.medianDecisionDate(),
                result.upperBoundDecisionDate(), "UNCALIBRATED_PROJECTION", result.uncertaintyDays(),
                result.uncertaintyExplanation(), result.snapshotDates(), 80, METHOD,
                deadlineType, deadline, conflict, warning, List.of());
    }

    @Transactional
    public Series selectSeries(String email, String rawFormType, String category, String office) {
        if (rawFormType == null || !rawFormType.matches("(?i)I-[0-9]{3}")
                || !FORMS.contains(rawFormType.toUpperCase())
                || category == null || category.isBlank() || office == null || office.isBlank()) {
            throw new IllegalArgumentException("A supported formType, formCategory and office are required");
        }
        String form = rawFormType.toUpperCase();
        long userId = users.findByEmail(email).orElseThrow().getId();
        String chosenCategory = category.trim();
        String chosenOffice = office.trim();
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM processing_time_snapshots
                WHERE source_name='USCIS_PROCESSING_TIMES' AND form_type=?
                  AND form_category=? AND office=? AND percentile=80
                """, Integer.class, form, chosenCategory, chosenOffice);
        if (count == null || count == 0) throw new IllegalArgumentException("The selected USCIS series is unavailable");
        jdbc.update("""
                INSERT INTO forecast_series_selections(user_id, form_type, form_category, office)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id, form_type) DO UPDATE SET
                  form_category=EXCLUDED.form_category, office=EXCLUDED.office
                """, userId, form, chosenCategory, chosenOffice);
        events.publishEvent(new ForecastRiskService.SelectionSaved(userId, form));
        return new Series(chosenCategory, chosenOffice);
    }

    private static LocalDate filingDate(UserProfile profile, String form) {
        if (profile == null) return null;
        return switch (form) {
            case "I-765" -> profile.getI765FilingDate();
            case "I-539" -> profile.getI539FilingDate();
            case "I-140" -> profile.getI140FilingDate();
            default -> null;
        };
    }

    private static String deadlineType(String form) {
        return switch (form) {
            case "I-765" -> "OPT_START_DATE";
            case "I-539" -> "VISA_EXPIRY_DATE";
            default -> null;
        };
    }

    private static LocalDate deadline(UserProfile profile, String form) {
        if (profile == null) return null;
        return switch (form) {
            case "I-765" -> profile.getOptStartDate();
            case "I-539" -> profile.getVisaExpiryDate();
            default -> null;
        };
    }

    public record Series(String category, String office) {}

    public record Forecast(String status, String reason, String formType, String formCategory, String office,
            LocalDate filingDate, LocalDate rangeStartDate, LocalDate medianDecisionDate,
            LocalDate upperBoundDecisionDate, String bandType, Integer uncertaintyDays,
            String uncertaintyExplanation, List<LocalDate> sourceSnapshotDates,
            Integer publishedPercentile, String method, String deadlineType, LocalDate deadlineDate,
            boolean deadlineConflict, String deadlineWarning, List<Series> availableSeries) {
        static Forecast unavailable(String formType, String reason) {
            return unavailable(formType, reason, List.of());
        }

        static Forecast unavailable(String formType, String reason, List<Series> series) {
            return new Forecast("UNAVAILABLE", reason, formType, null, null, null, null, null,
                    null, null, null, null, List.of(), null, METHOD, null, null, false, null, series);
        }
    }
}
