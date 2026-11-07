package mygrant.ingestion.classification;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import mygrant.policies.PolicyCountryRestriction;
import mygrant.policies.RestrictionCategory;
import mygrant.policies.RestrictionScope;

/** Conservative extractor for explicitly enumerated country lists in entry proclamations. */
@Component
public class EntryRestrictionExtractor {
    private static final Map<String, String> COUNTRIES = countryNames();
    private static final Pattern SECTION = Pattern.compile("(?is)((?:Section|Sec\\.)\\s+\\d+\\.?[^\\n.]{0,220})(.*?)(?=\\n\\s*(?:Section|Sec\\.)\\s+\\d+|\\z)");
    private static final Pattern COUNTRY = Pattern.compile("(?i)(?:nationals?\\s+of|from)\\s+([^.;\\n]{2,1000})");
    private static final Pattern COUNTRY_LIST_CUE = Pattern.compile("(?i)(?:full suspension|partial suspension|fully restrict|partially restrict)");
    private static final Pattern EXEMPTION = Pattern.compile("(?is)((?:Section|Sec\\.)\\s*6\\b.*?)(?=\\n\\s*(?:Section|Sec\\.)\\s*7\\b|\\z)");
    private static final Pattern PA_TRAVEL_DOCUMENT = Pattern.compile("(?i)(?:travel documents issued or endorsed by (?:the )?(?:Palestinian Authority|PA)|PA-issued or endorsed travel documents)");

    public Extraction extract(String title, String content, String sourceUrl) {
        String combined = (title == null ? "" : title) + "\n" + (content == null ? "" : content);
        boolean proclamation = combined.toLowerCase(Locale.ROOT).contains("proclamation")
                && (combined.toLowerCase(Locale.ROOT).contains("entry")
                    || combined.toLowerCase(Locale.ROOT).contains("suspend the entry")
                    || combined.toLowerCase(Locale.ROOT).contains("restricting and limiting the entry"));
        if (!proclamation) return new Extraction(false, false, false, List.of(), List.of(), List.of(), List.of());
        List<PolicyCountryRestriction> rules = new ArrayList<>();
        List<String> references = new ArrayList<>();
        boolean specialDocumentHolderRule = PA_TRAVEL_DOCUMENT.matcher(combined).find();
        boolean unparsedRestrictionSection = false;
        String exemption = "";
        Matcher ex = EXEMPTION.matcher(content == null ? "" : content);
        if (ex.find()) exemption = ex.group().strip();
        Matcher sections = SECTION.matcher(content == null ? "" : content);
        while (sections.find()) {
            String heading = sections.group(1);
            String body = sections.group(2);
            String section = heading.replaceAll("(?is)\\s+", " ").strip();
            RestrictionScope scope = scope(section);
            if (scope == null || !COUNTRY_LIST_CUE.matcher(section).find()) continue;
            Matcher lists = COUNTRY.matcher(body);
            int rulesBeforeSection = rules.size();
            while (lists.find()) {
                String countryText = lists.group(1);
                for (Map.Entry<String, String> country : COUNTRIES.entrySet()) {
                    if (containsCountry(countryText, country.getKey())) {
                        PolicyCountryRestriction rule = new PolicyCountryRestriction(country.getValue(), scope,
                                sourceUrl + "#" + section.replaceAll("[^A-Za-z0-9]+", "-"), exemption);
                        if (rules.stream().noneMatch(existing -> existing.getCountryCode().equals(rule.getCountryCode())
                                && existing.getScope() == rule.getScope())) rules.add(rule);
                    }
                }
            }
            if (rules.size() == rulesBeforeSection) unparsedRestrictionSection = true;
            if (!rules.isEmpty()) references.add(sourceUrl + "#" + section.replaceAll("[^A-Za-z0-9]+", "-"));
        }
        boolean uncertain = unparsedRestrictionSection || (rules.isEmpty() && !specialDocumentHolderRule);
        List<RestrictionCategory> categories = rules.isEmpty() ? List.of(RestrictionCategory.ENTRY_RESTRICTION)
                : List.of(RestrictionCategory.ENTRY_RESTRICTION, RestrictionCategory.TRAVEL_RESTRICTION);
        return new Extraction(true, uncertain, specialDocumentHolderRule, List.copyOf(rules), rules.stream()
                .map(PolicyCountryRestriction::getCountryCode).distinct().toList(), categories,
                references.stream().distinct().toList());
    }

    private static RestrictionScope scope(String heading) {
        String h = heading.toLowerCase(Locale.ROOT);
        if (h.contains("full suspension") || h.contains("fully restrict")) return RestrictionScope.FULL;
        if (h.contains("partial suspension") || h.contains("partially restrict")) return RestrictionScope.PARTIAL;
        return null;
    }
    private static boolean containsCountry(String text, String country) {
        String norm = normalize(text), name = normalize(country);
        return norm.contains(name);
    }
    private static String normalize(String s) {
        return " " + Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", " ").trim() + " ";
    }
    private static Map<String, String> countryNames() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String code : java.util.Locale.getISOCountries()) {
            String name = new Locale("", code).getDisplayCountry(Locale.ENGLISH);
            result.put(name, code);
        }
        result.put("Burma", "MM");
        result.put("Cote dIvoire", "CI");
        result.put("The Gambia", "GM");
        return Map.copyOf(result);
    }
    public record Extraction(boolean proclamation, boolean requiresReview, boolean specialDocumentHolderRule,
            List<PolicyCountryRestriction> restrictions, List<String> affectedCountries,
            List<RestrictionCategory> categories, List<String> sourceReferences) {}
}
