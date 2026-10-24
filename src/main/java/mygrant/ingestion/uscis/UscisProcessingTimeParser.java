package mygrant.ingestion.uscis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

/** Parses a saved, admin-reviewed transcription of the USCIS result card. */
@Component
public class UscisProcessingTimeParser {
    private static final Pattern HEADING = Pattern.compile(
            "(?im)^Processing time for .+?\\(([A-Z]{1,3}-\\d+[A-Z]?)\\) at ([^\\r\\n]+?)\\.?\\s*$");
    private static final Pattern P80 = Pattern.compile(
            "(?im)^80% of cases are completed within (\\d+(?:\\.\\d+)?) (Days?|Months?)\\.?\\s*$");

    public Observation parse(JsonNode saved, String sourceUrl, String contentHash) {
        String excerpt = required(saved, "sourceExcerpt");
        String category = required(saved, "formCategory");
        String dateText = required(saved, "observedOn");
        if (category.length() > 200 || excerpt.length() > 20_000) {
            throw new IllegalArgumentException("USCIS category or source excerpt is too long");
        }
        LocalDate observedOn;
        try {
            observedOn = LocalDate.parse(dateText);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("observedOn must be YYYY-MM-DD", exception);
        }
        if (observedOn.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("observedOn cannot be in the future");
        }
        Matcher heading = HEADING.matcher(excerpt);
        Matcher p80 = P80.matcher(excerpt);
        if (!heading.find() || !p80.find()) {
            throw new IllegalArgumentException("Saved USCIS result must include its form, office and 80% time");
        }
        String office = heading.group(2).trim();
        String formType = heading.group(1).toUpperCase();
        BigDecimal value = new BigDecimal(p80.group(1));
        String unit = p80.group(2).toUpperCase().startsWith("DAY") ? "DAYS" : "MONTHS";
        if (heading.find() || p80.find()) {
            throw new IllegalArgumentException("Saved USCIS result must contain exactly one result card");
        }
        if (office.isEmpty() || office.length() > 200) {
            throw new IllegalArgumentException("USCIS office is missing or too long");
        }
        if (value.signum() <= 0 || value.scale() > 2) {
            throw new IllegalArgumentException("USCIS processing time must be positive with at most two decimals");
        }
        return new Observation(formType, category, office, observedOn,
                value, unit, sourceUrl, contentHash, excerpt);
    }

    private String required(JsonNode saved, String field) {
        JsonNode value = saved.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("USCIS saved result requires " + field);
        }
        return value.asText().trim();
    }

    /** Median is deliberately absent: the current result card reports the 80th percentile. */
    public record Observation(String formType, String formCategory, String office,
            LocalDate observedOn, BigDecimal percentileValue, String durationUnit,
            String sourceUrl, String contentHash, String sourceExcerpt) {}
}
