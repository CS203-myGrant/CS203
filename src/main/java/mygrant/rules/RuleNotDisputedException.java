package mygrant.rules;

/** An admin asked to clear the dispute of a rule that is not disputed. */
public class RuleNotDisputedException extends RuntimeException {
    public RuleNotDisputedException(long ruleId) {
        super("Rule " + ruleId + " is not disputed");
    }
}
