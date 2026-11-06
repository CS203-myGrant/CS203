package mygrant.rules;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** What moved between two rule states. */
public enum RuleChange {
    STAGE,
    EFFECTIVE_DATE,
    DISPUTED,
    /** Only in the timeline: the caller's date has reached a final rule's effective date. Never stored. */
    TOOK_EFFECT,
    /**
     * Only in the timeline: a final rule on a rule that was already final, not about the effective
     * date. It is a separate final rule in the same rulemaking with its own effective date, and the
     * rule's stage and effective date are unchanged. Never raised in a PolicyChangedEvent.
     */
    ADDITIONAL_FINAL_RULE;

    /**
     * The changes from one state to the next: STAGE, EFFECTIVE_DATE and DISPUTED only. Everything
     * is new when there was no state.
     */
    public static Set<RuleChange> between(RuleState previous, RuleState current) {
        Set<RuleChange> changes = EnumSet.noneOf(RuleChange.class);
        if (previous == null || previous.primaryStage() != current.primaryStage()) {
            changes.add(STAGE);
        }
        if (!Objects.equals(previous == null ? null : previous.effectiveDate(), current.effectiveDate())) {
            changes.add(EFFECTIVE_DATE);
        }
        if ((previous != null && previous.disputed()) != current.disputed()) {
            changes.add(DISPUTED);
        }
        return changes;
    }
}
