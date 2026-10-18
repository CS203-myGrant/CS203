package mygrant.notifications;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * Explainable keyword rules linking policy text to the profile fields it affects.
 * This is a heuristic to prompt a review, not a legal determination.
 */
@Component
public class PolicyImpactRules {

    // Federal Register raw text is hard-wrapped, so a signal can arrive split over two
    // lines as "duration of \nstatus". Runs of whitespace collapse to a single space
    // before matching, otherwise multi-word signals never match a downloaded rule.
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    // Paperwork Reduction Act notices revise the forms schools and agencies file, not what
    // an applicant must do, so they still reach readers but never ask them to act. Matched
    // on the title: the API's document type is not stored, and "Notice" is too broad since
    // some notices, such as a STEM degree list update, do change what a student can do.
    private static final String INFORMATION_COLLECTION_PREFIX = "agency information collection activities";

    // How far either side of a signal a context word may sit, in characters of the
    // collapsed text. About a sentence or two: near enough that the word describes the same
    // provision, far enough to reach a subject named earlier in the sentence.
    static final int CONTEXT_WINDOW = 200;

    // Words that place a signal in a student or exchange visitor provision.
    private static final List<String> STUDENT_CONTEXT = List.of(
            "f-1", "j-1", "student", "students", "exchange visitor", "exchange visitors", "sevis",
            "designated school official", "responsible officer", "academic", "program completion",
            "completion of the program", "completion of studies");

    // Words that place a signal in an employment-based provision.
    private static final List<String> WORKER_CONTEXT = List.of(
            "h-1b", "employment", "employer", "employers", "worker", "workers", "cessation",
            "petitioner", "specialty occupation");

    // Words that place a signal in a provision about a person crossing the border. Exact
    // forms only: FR Doc 2024-12396 describes CBP's exit system with "departing" and "ports
    // of entry", and those passages are about the agency, not the traveller.
    private static final List<String> TRAVELLER_CONTEXT = List.of(
            "depart", "departure", "re-entry", "reentry", "port of entry", "inspection",
            "admission at");

    // Rules are scoped by visa type because one policy can be classified into several
    // categories. Without the scope, an H-1B reader would be told to review a field
    // that only the F-1 half of the policy touches.
    private static final List<Rule> RULES = List.of(
            // These phrases only describe how long a student or exchange visitor may stay,
            // so they need no surrounding context.
            Rule.of(ProfileField.PROGRAM_END_DATE, Set.of("F-1", "J-1"),
                    "duration of status", "date certain", "program end date"),
            // Workers have a period of admission and a grace period too; FR Doc 2026-18631
            // ends the 60-day grace period after an H-1B job ends. These count only where
            // the text around them is about students.
            Rule.near(ProfileField.PROGRAM_END_DATE, Set.of("F-1", "J-1"), STUDENT_CONTEXT,
                    "period of admission", "grace period"),
            // Practical training moves the work-authorization dates that follow a program
            // end date. It says nothing about which degree the student is enrolled in, so
            // it deliberately does not flag the academic level as well.
            Rule.of(ProfileField.PROGRAM_END_DATE, Set.of("F-1"),
                    "practical training", "stem opt"),
            Rule.of(ProfileField.ACADEMIC_LEVEL, Set.of("F-1", "J-1"),
                    "educational level", "academic level", "degree level", "change of program"),
            // An entry or exit rule that acts at the border changes what a worker with a trip
            // booked must do to leave and re-enter. Fee rules name the same programs without
            // touching the traveller (FR Docs 2024-12396 and 2026-17324 are paid by employers),
            // so the signal counts only where the text is about the person crossing.
            Rule.near(ProfileField.UPCOMING_TRAVEL, Set.of("H-1B"), TRAVELLER_CONTEXT,
                    "entry-exit", "biometric entry and exit"),
            // An H-1B worker's status depends on the sponsoring job, so a rule about losing
            // that job is one the worker has to check against their employer. Changing
            // employers is deliberately absent: FR Doc 2024-12396 exempts petitions "that do
            // not involve a change of employer", so the phrase matches its own negation.
            Rule.of(ProfileField.EMPLOYMENT, Set.of("H-1B"),
                    "cessation of employment", "loss of employment", "termination of employment"),
            // The worker counterpart of the student grace period rule above.
            Rule.near(ProfileField.EMPLOYMENT, Set.of("H-1B"), WORKER_CONTEXT,
                    "grace period"));

    /**
     * Returns the affected fields for each visa type the policy was classified into,
     * reading the policy text once for all of them. An information collection notice
     * affects no fields.
     *
     * @param visaTypes the visa categories the policy was classified into
     * @param title published policy title
     * @param content indexed policy excerpt, which may be null
     * @return affected fields keyed by normalized visa type
     */
    public Map<String, Set<ProfileField>> affectedFieldsByVisaType(Collection<String> visaTypes,
            String title, String content) {
        String searchable = WHITESPACE.matcher(title + " " + (content == null ? "" : content))
                .replaceAll(" ").toLowerCase(Locale.ROOT);

        return visaTypes.stream()
                .map(PolicyImpactRules::normalizeVisaType)
                .distinct()
                .collect(Collectors.toMap(visaType -> visaType,
                        visaType -> isInformationCollection(title)
                                ? Set.<ProfileField>of()
                                : matchingFields(visaType, searchable)));
    }

    private static boolean isInformationCollection(String title) {
        return title != null && title.strip().toLowerCase(Locale.ROOT).startsWith(INFORMATION_COLLECTION_PREFIX);
    }

    /** Compares visa types written with stray case or spacing as equal, such as {@code " f-1 "}. */
    public static String normalizeVisaType(String visaType) {
        return visaType == null ? "" : visaType.trim().toUpperCase(Locale.ROOT);
    }

    private Set<ProfileField> matchingFields(String visaType, String searchable) {
        Set<ProfileField> fields = EnumSet.noneOf(ProfileField.class);
        RULES.stream()
                .filter(rule -> rule.matches(visaType, searchable))
                .forEach(rule -> fields.add(rule.field()));
        return fields;
    }

    /**
     * One field, the visa types it applies to, the signals that trigger it and, for signals
     * too generic to stand alone, the context words one of which must sit nearby.
     */
    private record Rule(ProfileField field, Set<String> visaTypes, List<Pattern> signals,
            List<Pattern> context) {

        static Rule of(ProfileField field, Set<String> visaTypes, String... signals) {
            return new Rule(field, visaTypes, patterns(List.of(signals)), List.of());
        }

        static Rule near(ProfileField field, Set<String> visaTypes, List<String> context,
                String... signals) {
            return new Rule(field, visaTypes, patterns(List.of(signals)), patterns(context));
        }

        // Signals match on word boundaries so that an unrelated word ending in a signal,
        // such as "candidate certain", does not trigger a review.
        private static List<Pattern> patterns(List<String> words) {
            return words.stream()
                    .map(word -> Pattern.compile("\\b" + Pattern.quote(word) + "\\b"))
                    .toList();
        }

        boolean matches(String visaType, String text) {
            return visaTypes.contains(visaType)
                    && signals.stream().anyMatch(signal -> occursInContext(signal, text));
        }

        // A policy can use a signal in one provision about workers and another about
        // students, so every occurrence is checked; one in the right context is enough.
        private boolean occursInContext(Pattern signal, String text) {
            Matcher occurrence = signal.matcher(text);
            while (occurrence.find()) {
                if (context.isEmpty() || contextNear(text, occurrence.start(), occurrence.end())) {
                    return true;
                }
            }
            return false;
        }

        // Transparent bounds let the word boundary see past the window's edges, so a word
        // cut by the window, such as "unemployment", is not read as "employment".
        private boolean contextNear(String text, int start, int end) {
            int from = Math.max(0, start - CONTEXT_WINDOW);
            int to = Math.min(text.length(), end + CONTEXT_WINDOW);
            return context.stream().anyMatch(word -> word.matcher(text)
                    .region(from, to).useTransparentBounds(true).find());
        }
    }
}
