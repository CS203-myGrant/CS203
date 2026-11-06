package mygrant.rules;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import mygrant.impact.ProfileImpactAssessmentService;
import mygrant.policies.PolicyAssessmentInputs;
import mygrant.policies.PolicyDocument;
import mygrant.policies.PolicyDocumentRepository;
import mygrant.policies.PolicyRecommendationStore;

/**
 * Reassesses the users {@link RuleReassessmentScope} names against the document that caused a
 * rule change, through PolicyRecommendationStore, the same path as "See how this impacts me".
 * Its gates therefore apply: a result is published only when the judge approves it, otherwise it
 * waits for review, and its action items follow. The store keys a result by profile and document,
 * so a change delivered twice is assessed once. Runs after the change commits, on a background
 * thread, so an import never waits for the language model; one user's failure skips only them.
 */
@Component
public class RuleReassessmentListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuleReassessmentListener.class);

    private final RuleReassessmentScope scope;
    private final PolicyDocumentRepository policies;
    private final PolicyRecommendationStore recommendations;
    private final ProfileImpactAssessmentService assessments;

    public RuleReassessmentListener(RuleReassessmentScope scope, PolicyDocumentRepository policies,
            PolicyRecommendationStore recommendations, ProfileImpactAssessmentService assessments) {
        this.scope = scope;
        this.policies = policies;
        this.recommendations = recommendations;
        this.assessments = assessments;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPolicyChanged(PolicyChangedEvent event) {
        PolicyDocument target = policies.findById(event.source().policyId()).orElse(null);
        if (target == null) {
            return;
        }
        List<String> users = scope.usersToReassess(event);
        int failed = 0;
        for (String email : users) {
            try {
                recommendations.getOrGenerate(email, target, () -> assessments.assess(email,
                        PolicyAssessmentInputs.document(target), target.getAgency(), target.getContent(),
                        PolicyAssessmentInputs.classification(target)));
            } catch (RuntimeException failure) {
                failed++;
                LOGGER.warn("Could not reassess a user for rule {} change {}", event.ruleId(), event.historyId(),
                        failure);
            }
        }
        if (!users.isEmpty()) {
            LOGGER.info("Rule {} change {}: reassessed {} of {} user(s)", event.ruleId(), event.historyId(),
                    users.size() - failed, users.size());
        }
    }
}
