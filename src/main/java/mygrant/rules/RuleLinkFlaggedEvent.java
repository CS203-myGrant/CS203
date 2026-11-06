package mygrant.rules;

import mygrant.common.Confidence;

/**
 * Published when a document's link to its rule needs a person to check it: a title-only match,
 * identifiers that point to more than one rule, a stage that would move backwards, or a litigation
 * report on an unconfirmed link. The same flag is stored on policy_rule_documents (needs_review,
 * review_reason). Nothing listens yet: anomaly review (CS203-90) can consume this event or read
 * the flag. Not published by the backfill.
 */
public record RuleLinkFlaggedEvent(long policyId, long ruleId, MatchMethod method, Confidence confidence,
        String reason) {
}
