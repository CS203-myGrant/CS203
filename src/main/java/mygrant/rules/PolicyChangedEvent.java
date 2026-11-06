package mygrant.rules;

import java.util.List;
import java.util.Set;

/**
 * Raised once when a rule's stage moves, its effective date changes or news reports it disputed,
 * and handled after the change commits. Carries the old and new values and the document that
 * caused the change. A rule's first document, a repeat, an older document arriving late and
 * anything the backfill writes raise nothing.
 *
 * @param historyId the history row the change was recorded in
 * @param visaTypes the visa types of the rule's newest applied primary document, which decide who is alerted
 * @param primaryTitle title of that primary document; with primaryContent, what the impact rules read
 */
public record PolicyChangedEvent(
        long ruleId,
        long historyId,
        String ruleTitle,
        RuleState previous,
        RuleState current,
        Set<RuleChange> changes,
        RuleSource source,
        List<String> visaTypes,
        String primaryTitle,
        String primaryContent
) {
    public PolicyChangedEvent {
        changes = Set.copyOf(changes);
        visaTypes = List.copyOf(visaTypes);
    }
}
