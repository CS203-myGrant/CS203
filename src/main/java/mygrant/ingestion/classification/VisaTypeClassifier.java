package mygrant.ingestion.classification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Applies explainable keyword rules to identify supported visa categories.
 * Explicit matches in a policy title take priority over incidental mentions in its body.
 */
@Component
public class VisaTypeClassifier {

    private static final Map<String, List<String>> SIGNALS = new LinkedHashMap<>();

    static {
        SIGNALS.put("F-1", List.of(
                "f-1", "f\u20131", "academic student", "optional practical training",
                "stem opt", "form i-20", "designated school official", "sevis"));
        SIGNALS.put("J-1", List.of(
                "j-1", "j\u20131", "exchange visitor", "form ds-2019",
                "responsible officer"));
        SIGNALS.put("H-1B", List.of(
                "h-1b", "h\u20131b", "specialty occupation", "labor condition application",
                "h-1b cap"));
    }

    // In full text, a visa type counts only if its signals occur at least this share as
    // often as the most frequent type's. A rule about one category often compares it with
    // others in background or footnotes: FR Doc 2026-18631 names H-1B 189 times and F-1
    // three times, once in a footnote contrasting the F-1 grace period.
    static final double MIN_SHARE_OF_TOP_COUNT = 0.25;

    /**
     * Classifies a document using substring signals in its title or, if the title has
     * no supported signal, how often they occur in its full text. This is a heuristic,
     * not a legal determination.
     *
     * @param title published document title
     * @param content downloaded full text
     * @return supported visa categories and the signals that matched
     */
    public VisaClassification classify(String title, String content) {
        String normalizedTitle = title.toLowerCase(Locale.ROOT);
        // A Federal Register rule may mention other visa programs only in
        // comparisons or background. When its title names supported categories,
        // use those explicit signals instead of incidental full-text mentions.
        boolean titleHasSignal = SIGNALS.values().stream()
                .flatMap(List::stream).anyMatch(normalizedTitle::contains);
        return titleHasSignal
                ? classifyTitle(normalizedTitle)
                : classifyFullText(content.toLowerCase(Locale.ROOT));
    }

    private VisaClassification classifyTitle(String normalizedTitle) {
        List<String> visaTypes = new ArrayList<>();
        List<String> matchedSignals = new ArrayList<>();

        SIGNALS.forEach((visaType, signals) -> {
            List<String> matches = signals.stream().filter(normalizedTitle::contains).toList();
            if (!matches.isEmpty()) {
                visaTypes.add(visaType);
                matches.forEach(signal -> matchedSignals.add(visaType + ":" + signal));
            }
        });
        return result(visaTypes, matchedSignals);
    }

    private VisaClassification classifyFullText(String text) {
        Map<String, Map<String, Integer>> countsByVisaType = new LinkedHashMap<>();
        SIGNALS.forEach((visaType, signals) -> {
            Map<String, Integer> counts = new LinkedHashMap<>();
            signals.forEach(signal -> {
                int count = occurrences(text, signal);
                if (count > 0) {
                    counts.put(signal, count);
                }
            });
            if (!counts.isEmpty()) {
                countsByVisaType.put(visaType, counts);
            }
        });
        int topCount = countsByVisaType.values().stream().mapToInt(VisaTypeClassifier::total).max().orElse(0);

        List<String> visaTypes = new ArrayList<>();
        List<String> matchedSignals = new ArrayList<>();
        // Counts go into the evidence so a reader can see why a type was kept or dropped.
        countsByVisaType.forEach((visaType, counts) -> {
            int total = total(counts);
            if (total >= MIN_SHARE_OF_TOP_COUNT * topCount) {
                visaTypes.add(visaType);
                counts.forEach((signal, count) -> matchedSignals.add(visaType + ":" + signal + " x" + count));
            } else {
                matchedSignals.add(visaType + ":dropped, " + total + " mentions is under "
                        + Math.round(MIN_SHARE_OF_TOP_COUNT * 100) + "% of the top count " + topCount);
            }
        });
        return result(visaTypes, matchedSignals);
    }

    private static int total(Map<String, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static int occurrences(String text, String signal) {
        int count = 0;
        for (int at = text.indexOf(signal); at >= 0; at = text.indexOf(signal, at + signal.length())) {
            count++;
        }
        return count;
    }

    private static VisaClassification result(List<String> visaTypes, List<String> matchedSignals) {
        return new VisaClassification(
                visaTypes.contains("F-1"),
                visaTypes.contains("J-1"),
                visaTypes.contains("H-1B"),
                List.copyOf(visaTypes),
                List.copyOf(matchedSignals));
    }
}
