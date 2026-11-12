package mygrant.impact;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** Capture review eligibility when a result is saved; human approval remains a separate decision. */
@Component
@EnableConfigurationProperties(ImpactAssessmentProperties.class)
public class AssessmentReviewPolicy {
    private final ImpactAssessmentProperties properties;

    public AssessmentReviewPolicy(ImpactAssessmentProperties properties) {
        this.properties = properties;
    }

    public boolean requiresReview(ProfileImpactAssessment assessment) {
        return assessment.requiresHumanReview() || assessment.status() == AssessmentStatus.UNAVAILABLE
                || (assessment.evaluation() != null && assessment.evaluation().verdict() != JudgeEvaluation.Verdict.YES)
                || assessment.confidence() < properties.reviewConfidenceThreshold();
    }

    public ProfileImpactAssessment apply(ProfileImpactAssessment assessment) {
        boolean review = requiresReview(assessment);
        return new ProfileImpactAssessment(assessment.urgency(), assessment.confidence(), assessment.summary(),
                assessment.reasons(), assessment.recommendedActions(), assessment.missingInformation(),
                review, assessment.provider(), assessment.model(), assessment.evaluation(), assessment.attempts(),
                assessment.status());
    }
}
