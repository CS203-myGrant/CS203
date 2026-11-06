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
 */
final class LitigationSignal {

    static final List<String> PHRASES = List.of("lawsuit", "lawsuits", "sued", "sues", "suing",
            "injunction", "enjoined", "enjoins", "temporary restraining order", "restraining order",
            "vacated", "vacatur", "stay of the rule", "stayed the rule", "court stay",
            "blocked by a judge", "blocked by a federal judge", "blocked by a court",
            "judge blocks", "judge blocked", "court blocks", "court blocked");

    private static final Pattern PATTERN = Pattern.compile(PHRASES.stream()
            .map(phrase -> phrase.replace(" ", "\\s+"))
            .collect(Collectors.joining("|", "(?i)\\b(?:", ")\\b")));

    private LitigationSignal() {
    }

    static boolean reports(String text) {
        return text != null && PATTERN.matcher(text).find();
    }
}
