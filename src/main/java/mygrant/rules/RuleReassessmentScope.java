package mygrant.rules;

import java.util.List;

/**
 * Who is reassessed when a rule changes: the hook CS203-153 widens to every matching profile. The
 * default, {@link EarlierAssessmentScope}, takes only users who already have an assessment for an
 * earlier document of the same rule. To replace it, register another implementation as @Primary.
 */
public interface RuleReassessmentScope {

    /** The emails of the users to reassess against the document that caused the change. */
    List<String> usersToReassess(PolicyChangedEvent event);
}
