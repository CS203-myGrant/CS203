package mygrant.rules;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Whether a news item reports litigation or a court stay. Only phrases about courts count:
 * "stay" alone is everywhere in immigration text ("length of stay", "extension of stay"), so a
 * stay counts only as "stay of the rule", "stayed the rule" or "court stay". "sue" is left out
 * because matching ignores case and it would hit the name Sue; "sued", "sues", "suing" and
 * "lawsuit" cover the real reports.
 *
 * <p>Headlines about a court order name the court or judge and what it did, with a word or two
 * between: "Court Temporarily Blocks Rule …", "Federal Court Postpones Effective Date …". So
 * "court" or "judge" followed, within the next three words, by one of {@link #COURT_ACTIONS}
 * counts. An agency doing the same ("DHS postpones the effective date") does not, and neither does
 * a court that "stays out of" a dispute.
 */
final class LitigationSignal {

    static final List<String> PHRASES = List.of("lawsuit", "lawsuits", "sued", "sues", "suing",
            "injunction", "enjoined", "enjoins", "temporary restraining order", "restraining order",
            "vacated", "vacatur", "stay of the rule", "stayed the rule", "court stay",
            "blocked by a judge", "blocked by a federal judge", "blocked by a court");

    /** What a court or judge does to a rule. */
    static final List<String> COURT_ACTIONS = List.of("blocks", "blocked", "halts", "halted", "pauses", "paused",
            "postpones", "postponed", "stays", "stayed", "enjoins", "enjoined");

    private static final Pattern PHRASE = Pattern.compile(PHRASES.stream()
            .map(phrase -> phrase.replace(" ", "\\s+"))
            .collect(Collectors.joining("|", "(?i)\\b(?:", ")\\b")));

    /**
     * "court" or "judge", up to two words, then the action: "US federal judge blocks", "court
     * temporarily blocks". Not when "out" follows: "Supreme Court stays out of student visa fight".
     */
    private static final Pattern COURT_ACTION = Pattern.compile("(?i)\\b(?:courts?|judges?)\\b"
            + "(?:\\s+[\\p{L}\\p{N}'’.-]+){0,2}?\\s+(?:" + String.join("|", COURT_ACTIONS) + ")\\b(?!\\s+out\\b)");

    private LitigationSignal() {
    }

    static boolean reports(String text) {
        return text != null && (PHRASE.matcher(text).find() || COURT_ACTION.matcher(text).find());
    }
}
