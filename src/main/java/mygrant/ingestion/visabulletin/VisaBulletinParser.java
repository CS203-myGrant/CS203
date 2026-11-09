package mygrant.ingestion.visabulletin;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/** Reads the four cutoff charts; rejects an incomplete or changed layout. */
@Component
public class VisaBulletinParser {
    private static final Pattern TITLE = Pattern.compile(
            "^Visa Bulletin For\\s+([A-Za-z]+)\\s+(\\d{4})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE = Pattern.compile("(\\d{2})([A-Z]{3})(\\d{2})");
    private static final DateTimeFormatter FULL_MONTH = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("MMMM").toFormatter(Locale.ENGLISH);
    private static final DateTimeFormatter SHORT_MONTH = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("MMM").toFormatter(Locale.ENGLISH);
    private static final Set<String> AREAS = Set.of(
            "ALL CHARGEABILITY AREAS EXCEPT THOSE LISTED", "CHINA-MAINLAND BORN",
            "INDIA", "MEXICO", "PHILIPPINES");
    private static final Set<String> FAMILY = Set.of("F1", "F2A", "F2B", "F3", "F4");
    private static final Set<String> EMPLOYMENT = Set.of("1ST", "2ND", "3RD", "OTHER WORKERS",
            "4TH", "CERTAIN RELIGIOUS WORKERS", "5TH UNRESERVED",
            "5TH SET ASIDE: RURAL", "5TH SET ASIDE: HIGH UNEMPLOYMENT",
            "5TH SET ASIDE: INFRASTRUCTURE");

    public Bulletin parse(String html, YearMonth expectedMonth, String sourceUrl) {
        Bulletin bulletin = parse(html, sourceUrl);
        if (!bulletin.publicationMonth().equals(expectedMonth)) {
            throw new IllegalArgumentException("Visa Bulletin publication month does not match request: "
                    + "expected " + expectedMonth + ", found " + bulletin.publicationMonth());
        }
        return bulletin;
    }

    /** Saved pages identify their own publication month; no month is supplied by the admin. */
    public Bulletin parse(String html, String sourceUrl) {
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException("Visa Bulletin page is empty");
        }
        var document = Jsoup.parse(html, sourceUrl);
        YearMonth publishedMonth = publicationMonth(document.select("h1"));
        List<Entry> entries = new ArrayList<>();
        Set<Section> seen = new HashSet<>();
        Section pending = null;
        for (Element element : document.select("h1, h2, h3, h4, p, table")) {
            if (element.normalName().equals("table")) {
                if (pending != null) {
                    if (!seen.add(pending)) {
                        throw new IllegalArgumentException("Repeated Visa Bulletin chart: " + pending);
                    }
                    entries.addAll(parseTable(element, pending, publishedMonth));
                    pending = null;
                }
            } else if (element.closest("table") == null) {
                Section heading = sectionFor(normalize(element.text()));
                if (heading != null) {
                    if (pending != null) {
                        throw new IllegalArgumentException("Missing table after chart: " + pending);
                    }
                    pending = heading;
                }
            }
        }
        if (pending != null || seen.size() != 4 || entries.size() != 150) {
            throw new IllegalArgumentException("Visa Bulletin must contain four complete charts");
        }
        return new Bulletin(publishedMonth, sourceUrl, List.copyOf(entries));
    }

    private YearMonth publicationMonth(List<Element> titles) {
        for (Element title : titles) {
            Matcher match = TITLE.matcher(normalize(title.text()));
            if (match.find()) {
                try {
                    Month month = Month.from(FULL_MONTH.parse(match.group(1)));
                    return YearMonth.of(Integer.parseInt(match.group(2)), month);
                } catch (DateTimeException exception) {
                    throw new IllegalArgumentException("Invalid Visa Bulletin month", exception);
                }
            }
        }
        throw new IllegalArgumentException("This HTML does not look like a Visa Bulletin page.");
    }

    private List<Entry> parseTable(Element table, Section section, YearMonth publicationMonth) {
        List<Element> rows = table.select("tr");
        if (rows.size() < 2) {
            throw new IllegalArgumentException("Visa Bulletin chart has no data rows");
        }
        List<Element> headers = cells(rows.get(0));
        if (headers.size() != 6) {
            throw new IllegalArgumentException("Visa Bulletin chargeability columns changed");
        }
        List<String> areas = new ArrayList<>();
        for (int column = 1; column < headers.size(); column++) {
            areas.add(normalize(headers.get(column).text()).toUpperCase(Locale.ROOT));
        }
        if (!Set.copyOf(areas).equals(AREAS) || areas.size() != AREAS.size()) {
            throw new IllegalArgumentException("Visa Bulletin chargeability columns changed");
        }
        List<Entry> entries = new ArrayList<>();
        Set<String> categories = new HashSet<>();
        for (int row = 1; row < rows.size(); row++) {
            List<Element> values = cells(rows.get(row));
            if (values.size() == 1 && values.get(0).hasAttr("colspan")) {
                continue;
            }
            if (values.size() != 6) {
                throw new IllegalArgumentException("Visa Bulletin chart column count changed");
            }
            String category = category(normalize(values.get(0).text()).toUpperCase(Locale.ROOT));
            if (!categories.add(category)) {
                throw new IllegalArgumentException("Repeated Visa Bulletin category: " + category);
            }
            for (int column = 1; column < values.size(); column++) {
                entries.add(cutoff(section, category, areas.get(column - 1),
                        normalize(values.get(column).text()).toUpperCase(Locale.ROOT), publicationMonth));
            }
        }
        Set<String> expected = section.preference().equals("FAMILY") ? FAMILY : EMPLOYMENT;
        if (!categories.equals(expected)) {
            throw new IllegalArgumentException("Visa Bulletin chart categories changed or are missing");
        }
        return entries;
    }

    private String category(String raw) {
        if (raw.startsWith("5TH UNRESERVED")) return "5TH UNRESERVED";
        if (raw.startsWith("5TH SET ASIDE")) {
            if (raw.contains("HIGH UNEMPLOYMENT")) return "5TH SET ASIDE: HIGH UNEMPLOYMENT";
            if (raw.contains("INFRASTRUCTURE")) return "5TH SET ASIDE: INFRASTRUCTURE";
            if (raw.contains("RURAL")) return "5TH SET ASIDE: RURAL";
        }
        return raw;
    }

    private Entry cutoff(Section section, String category, String area, String value,
            YearMonth publicationMonth) {
        if (value.equals("C")) {
            return new Entry(section.preference(), section.chart(), category, area, "CURRENT", null);
        }
        if (value.equals("U")) {
            return new Entry(section.preference(), section.chart(), category, area, "UNAVAILABLE", null);
        }
        Matcher match = DATE.matcher(value);
        if (!match.matches()) {
            throw new IllegalArgumentException("Unknown Visa Bulletin cutoff: " + value);
        }
        int shortYear = Integer.parseInt(match.group(3));
        int century = shortYear <= publicationMonth.getYear() % 100 + 1 ? 2000 : 1900;
        try {
            LocalDate date = LocalDate.of(century + shortYear,
                    Month.from(SHORT_MONTH.parse(match.group(2))), Integer.parseInt(match.group(1)));
            return new Entry(section.preference(), section.chart(), category, area, "DATE", date);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("Invalid Visa Bulletin cutoff: " + value, exception);
        }
    }

    private Section sectionFor(String text) {
        String heading = text.toUpperCase(Locale.ROOT).replaceFirst("^[A-D]\\.\\s*", "");
        String preference;
        if (heading.contains("FAMILY-SPONSORED")) preference = "FAMILY";
        else if (heading.contains("EMPLOYMENT-BASED")) preference = "EMPLOYMENT";
        else return null;
        if (heading.startsWith("FINAL ACTION DATES FOR ")) return new Section(preference, "FINAL_ACTION");
        if (heading.startsWith("DATES FOR FILING ")) return new Section(preference, "DATES_FOR_FILING");
        return null;
    }

    private List<Element> cells(Element row) {
        return row.children().stream()
                .filter(child -> child.normalName().equals("td") || child.normalName().equals("th"))
                .toList();
    }

    private String normalize(String value) {
        return value.replace('\u00a0', ' ').replaceAll("\\s+", " ")
                .replaceAll("-\\s+", "-").trim();
    }

    private record Section(String preference, String chart) {}

    public record Bulletin(YearMonth publicationMonth, String sourceUrl, List<Entry> entries) {}

    public record Entry(String preference, String chart, String category, String chargeabilityArea,
            String cutoffKind, LocalDate cutoffDate) {}
}
