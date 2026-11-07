package mygrant.rules;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Month;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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
 *
 * <p>Such a document's new date is read from its DATES paragraph, never from the Federal Register
 * API's effective_on: for the real delay notice 2021-02665 that is 8 Jan 2021, the date the
 * delayed rule was published, while DATES says "is delayed to December 31, 2021".
 */
public final class DateNotice {

    private static final Pattern ACTION = Pattern.compile("(?m)^[ \\t]*ACTION:[ \\t]*");
    private static final Pattern BLANK_LINE = Pattern.compile("\\R[ \\t]*\\R");
    /** A delay, a postponement or a change of effective date: the document should give a new date. */
    private static final Pattern NEW_DATE_NOTICE = Pattern.compile("(?i)\\b(?:delay|delays|delayed|delaying"
            + "|postpone|postpones|postponed|postponing|postponement|effective\\s+dates?)\\b");
    /** A correction can fix the date, but usually fixes text and gives no new date. */
    private static final Pattern CORRECTION = Pattern.compile("(?i)\\b(?:correction|corrections|correcting)\\b");
    /** The front matter's "DATES:" (or "DATE:") label, at the start of a line. */
    private static final Pattern DATES = Pattern.compile("(?m)^[ \\t]*DATES?:[ \\t]*");
    /** Where the DATES paragraph ends: the next all-capitals label, such as "ADDRESSES:", or the end of the text. */
    private static final Pattern NEXT_LABEL = Pattern.compile("(?m)^[ \\t]*(?:[A-Z][A-Z ,/&()'-]*[A-Z]:|</pre>)");
    /** The front matter is near the top; DATES further down would be in the body. */
    private static final int MAX_DATES_SEARCH = 50_000;
    private static final int MAX_DATES_CHARACTERS = 3_000;
    private static final String DATE = "(January|February|March|April|May|June|July|August|September|October"
            + "|November|December)\\s+(\\d{1,2}),?\\s+(\\d{4})";
    /**
     * "is delayed to December 31, 2021", "postponed until …", "the new effective date is …". The
     * noun is left out on purpose: 2021-02665 also says "comments on this delay until March 10".
     */
    private static final Pattern NEW_DATE = Pattern.compile("(?i)\\b(?:(?:delayed|postponed)\\s+(?:to|until)"
            + "|new\\s+effective\\s+date\\s+(?:is|will\\s+be|of))\\s+(?:on\\s+)?" + DATE + "\\b");

    private DateNotice() {
    }

    /** True when the ACTION line or the title's stage suffix says delay, postponement, effective date or correction. */
    public static boolean concernsEffectiveDate(String title, String content) {
        return says(NEW_DATE_NOTICE, title, content) || says(CORRECTION, title, content);
    }

    /**
     * True when the ACTION line or the title's stage suffix says delay, postponement or effective
     * date, so the document should give a new date: when none can be read, someone should look.
     * A correction alone is not: most fix text, and their DATES names no new date.
     */
    static boolean announcesNewDate(String title, String content) {
        return says(NEW_DATE_NOTICE, title, content);
    }

    private static boolean says(Pattern words, String title, String content) {
        if (action(content).filter(action -> words.matcher(action).find()).isPresent()) {
            return true;
        }
        return title != null && Arrays.stream(title.split(";")).skip(1).anyMatch(part -> words.matcher(part).find());
    }

    /**
     * The new effective date in the DATES paragraph of a document about the date. Empty when
     * there is no DATES paragraph, it names no new date in a wording read here, or it names more
     * than one, so the caller never guesses.
     */
    static Optional<LocalDate> newEffectiveDate(String content) {
        Optional<String> dates = dates(content);
        if (dates.isEmpty()) {
            return Optional.empty();
        }
        Set<LocalDate> found = new LinkedHashSet<>();
        Matcher match = NEW_DATE.matcher(dates.get());
        while (match.find()) {
            try {
                found.add(LocalDate.of(Integer.parseInt(match.group(3)),
                        Month.valueOf(match.group(1).toUpperCase(Locale.ROOT)), Integer.parseInt(match.group(2))));
            } catch (DateTimeException invalid) {
                // "February 30" and the like: not a date, so not a new effective date.
            }
        }
        return found.size() == 1 ? Optional.of(found.iterator().next()) : Optional.empty();
    }

    /**
     * The DATES paragraph of the front matter, after "SUMMARY:", with its whitespace collapsed. It
     * runs to the next all-capitals label, such as "ADDRESSES:" or "FOR FURTHER INFORMATION CONTACT:".
     */
    static Optional<String> dates(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        int summary = content.indexOf("SUMMARY:");
        int from = Math.max(summary, 0);
        String front = content.substring(from, Math.min(content.length(), from + MAX_DATES_SEARCH));
        Matcher label = DATES.matcher(front);
        if (!label.find()) {
            return Optional.empty();
        }
        String rest = front.substring(label.end());
        Matcher next = NEXT_LABEL.matcher(rest);
        String paragraph = next.find() ? rest.substring(0, next.start()) : rest;
        paragraph = paragraph.substring(0, Math.min(paragraph.length(), MAX_DATES_CHARACTERS));
        String dates = paragraph.replaceAll("\\s+", " ").strip();
        return dates.isEmpty() ? Optional.empty() : Optional.of(dates);
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
