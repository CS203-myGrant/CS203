package mygrant.rules;

/** The policy document exists but is in no rule: news that matched none, or a document not yet backfilled. */
public class PolicyRuleNotFoundException extends RuntimeException {
    public PolicyRuleNotFoundException(Long policyId) {
        super("Policy document " + policyId + " is not part of a tracked rule");
    }
}
