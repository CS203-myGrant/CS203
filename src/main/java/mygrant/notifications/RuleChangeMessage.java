package mygrant.notifications;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import mygrant.policies.PolicyStatus;
import mygrant.rules.RuleChange;
import mygrant.rules.RuleState;

/**
 * The text of a rule-change alert, which says what moved instead of "New policy":
 * <ul>
 * <li>"Rule update for F-1, J-1: moved from proposed to final, effective 15 Sep 2026: TITLE"</li>
 * <li>"Rule update for F-1, J-1: effective date moved from 15 Sep 2026 to 1 Nov 2026: TITLE"</li>
 * <li>"Rule update for F-1, J-1: status disputed: news reports litigation or a stay (The Guardian).
 * The official status is still final, effective 1 Nov 2026: TITLE"</li>
 * </ul>
 */
final class RuleChangeMessage {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US);

    private RuleChangeMessage() {
    }

    static String of(List<String> visaTypes, String ruleTitle, RuleState previous, RuleState current,
            Set<RuleChange> changes, String sourceAgency) {
        String what;
        if (changes.contains(RuleChange.DISPUTED)) {
            what = "status disputed: news reports litigation or a stay (" + sourceAgency
                    + "). The official status is still " + stage(current.primaryStage()) + effective(current.effectiveDate());
        } else if (changes.contains(RuleChange.STAGE)) {
            what = "moved from " + stage(previous.primaryStage()) + " to " + stage(current.primaryStage());
            if (changes.contains(RuleChange.EFFECTIVE_DATE)) {
                what += previous.effectiveDate() == null ? effective(current.effectiveDate())
                        : "; " + dateMove(previous.effectiveDate(), current.effectiveDate());
            }
        } else if (previous.effectiveDate() == null) {
            what = "effective date set to " + DATE.format(current.effectiveDate());
        } else {
            what = dateMove(previous.effectiveDate(), current.effectiveDate());
        }
        return "Rule update for " + String.join(", ", visaTypes) + ": " + what + ": " + ruleTitle;
    }

    private static String dateMove(LocalDate from, LocalDate to) {
        return "effective date moved from " + DATE.format(from) + " to " + DATE.format(to);
    }

    private static String effective(LocalDate date) {
        return date == null ? "" : ", effective " + DATE.format(date);
    }

    private static String stage(PolicyStatus stage) {
        return stage.name().toLowerCase(Locale.ROOT);
    }
}
