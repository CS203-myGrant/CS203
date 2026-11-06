package mygrant.rules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The title fallback, used only when no docket, RIN or linked document number matched. Two
 * titles are compared by Jaccard similarity: the words they share divided by all the words either
 * has, after both are normalised. A match at or above {@link #THRESHOLD} is only ever a
 * low-confidence suggestion, flagged for review.
 *
 * <p>Normalising lower-cases the title, drops trailing "; …" parts that name the document's stage
 * rather than the rule ("; Proposed Rule", "; Final Rule", "; Correction", "; Delay of Effective
 * Date" and the others in {@link #STAGE_SUFFIX}), splits it into words of letters and digits
 * (keeping hyphenated words such as "f-1" whole) and removes {@link #STOPWORDS}.
 */
public final class TitleSimilarity {

    /**
     * 0.8. The fixture rule's title has 17 words once stopwords go, so a title with one word
     * replaced scores 16/18 = 0.89 and one with four words added 17/21 = 0.81, both matches, but
     * one with two words replaced scores 15/19 = 0.79 and does not.
     */
    public static final double THRESHOLD = 0.8;

    /** Words that carry no meaning in a rule title. */
    public static final Set<String> STOPWORDS = Set.of("a", "an", "and", "are", "as", "at", "be", "by", "for",
            "from", "in", "into", "is", "its", "of", "on", "or", "than", "that", "the", "their", "this", "to",
            "under", "with", "within");

    /** A "; …" part naming the document's stage. */
    static final Pattern STAGE_SUFFIX = Pattern.compile("(?i)^\\s*(?:notice of )?(?:proposed rule(?:making)?"
            + "|interim final rule|final rule|rule|correction|corrections|correcting amendments?"
            + "|delay of effective date|withdrawal(?: of proposed rule)?|extension of comment period"
            + "|reopening of (?:the )?comment period|request for comments?)\\s*\\.?\\s*$");

    private static final Pattern WORD = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    private TitleSimilarity() {
    }

    /** Jaccard similarity of the two normalised titles, from 0 to 1; 0 when either has no words. */
    public static double similarity(String first, String second) {
        Set<String> a = words(first);
        Set<String> b = words(second);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> shared = new HashSet<>(a);
        shared.retainAll(b);
        Set<String> all = new HashSet<>(a);
        all.addAll(b);
        return (double) shared.size() / all.size();
    }

    static Set<String> words(String title) {
        Set<String> words = new LinkedHashSet<>();
        if (title == null) {
            return words;
        }
        List<String> parts = new ArrayList<>(Arrays.asList(title.toLowerCase(Locale.ROOT).split(";")));
        while (parts.size() > 1 && STAGE_SUFFIX.matcher(parts.getLast()).matches()) {
            parts.removeLast();
        }
        Matcher word = WORD.matcher(String.join(" ", parts));
        while (word.find()) {
            if (!STOPWORDS.contains(word.group())) {
                words.add(word.group());
            }
        }
        return words;
    }
}
