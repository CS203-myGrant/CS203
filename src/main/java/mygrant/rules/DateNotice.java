package mygrant.rules;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a document is about a rule's effective date: a delay, a postponement or a correction,
 * such as "ACTION: Final rule; delay of effective date." On a rule that is already final, only
 * such a document moves the effective date. Any other final document there is a separate final
 * rule in the same rulemaking, like the two final rules DHS issued on the 2023 H-1B proposal.
 *
 * <p>policy_documents keeps no action column (see V34), so the ACTION line is read from the
 * stored header, before "SUMMARY:", the same way identifiers are. The title's trailing "; …"
 * parts are read too, such as "; Delay of Effective Date" or "; Correction". The main title
 * before the first ";" is not, because it names the rule rather than the document.
 */
final class DateNotice {

    private static final Pattern ACTION = Pattern.compile("(?m)^[ \\t]*ACTION:[ \\t]*");
    private static final Pattern BLANK_LINE = Pattern.compile("\\R[ \\t]*\\R");
    private static final Pattern ABOUT_THE_DATE = Pattern.compile("(?i)\\b(?:delay|delays|delayed|delaying"
            + "|postpone|postpones|postponed|postponing|postponement|effective\\s+dates?"
            + "|correction|corrections|correcting)\\b");

    private DateNotice() {
    }

    /** True when the ACTION line or the title's stage suffix says delay, postponement, effective date or correction. */
    static boolean concernsEffectiveDate(String title, String content) {
        if (action(content).filter(action -> ABOUT_THE_DATE.matcher(action).find()).isPresent()) {
            return true;
        }
        if (title == null) {
            return false;
        }
        return Arrays.stream(title.split(";")).skip(1).anyMatch(part -> ABOUT_THE_DATE.matcher(part).find());
    }

    /** The header's ACTION paragraph, which can wrap onto further lines, with its whitespace collapsed. */
    static Optional<String> action(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        String header = RuleIdentifiers.header(content);
        Matcher label = ACTION.matcher(header);
        if (!label.find()) {
            return Optional.empty();
        }
        String rest = header.substring(label.end());
        Matcher blank = BLANK_LINE.matcher(rest);
        String paragraph = blank.find() ? rest.substring(0, blank.start()) : rest;
        String action = paragraph.replaceAll("\\s+", " ").strip();
        return action.isEmpty() ? Optional.empty() : Optional.of(action);
    }
}
