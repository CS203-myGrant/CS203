package mygrant.rules;

import mygrant.policies.PolicyStatus;

/**
 * How a document's status becomes a rule stage. PROPOSED, FINAL and WITHDRAWN are stages and only
 * move forward, in that order. GUIDANCE and UNKNOWN carry no stage: they can start a rule but never
 * move one. EFFECTIVE is derived when a rule is read, so a document marked EFFECTIVE counts as
 * FINAL. CHALLENGED is not a stage: litigation is the separate disputed flag.
 */
final class RuleStages {

    private RuleStages() {
    }

    static PolicyStatus of(PolicyStatus documentStatus) {
        if (documentStatus == null) {
            return PolicyStatus.UNKNOWN;
        }
        return switch (documentStatus) {
            case PROPOSED -> PolicyStatus.PROPOSED;
            case FINAL, EFFECTIVE -> PolicyStatus.FINAL;
            case WITHDRAWN -> PolicyStatus.WITHDRAWN;
            case GUIDANCE -> PolicyStatus.GUIDANCE;
            case CHALLENGED, UNKNOWN -> PolicyStatus.UNKNOWN;
        };
    }

    static boolean isStage(PolicyStatus stage) {
        return rank(stage) > 0;
    }

    static int rank(PolicyStatus stage) {
        return switch (stage) {
            case PROPOSED -> 1;
            case FINAL -> 2;
            case WITHDRAWN -> 3;
            default -> 0;
        };
    }

    /** The stage after a document of docStage is applied to a rule at current; never backwards. */
    static PolicyStatus next(PolicyStatus current, PolicyStatus docStage) {
        if (rank(docStage) > rank(current)) {
            return docStage;
        }
        if (current == PolicyStatus.UNKNOWN && docStage == PolicyStatus.GUIDANCE) {
            return PolicyStatus.GUIDANCE;
        }
        return current;
    }

    /** True when a stage-bearing document would move a staged rule backwards, e.g. PROPOSED after FINAL. */
    static boolean isBackward(PolicyStatus current, PolicyStatus docStage) {
        return isStage(docStage) && isStage(current) && rank(docStage) < rank(current);
    }
}
