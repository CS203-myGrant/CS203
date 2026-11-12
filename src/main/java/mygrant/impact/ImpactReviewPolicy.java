package mygrant.impact;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The one rule that decides whether a completed AI assessment reaches its owner straight away or
 * waits in the reviewer queue first. Every path that publishes an assessment uses it: background
 * assessment after ingestion, the policy page, rule-change reassessment and admin re-evaluation.
 * <p>
 * An assessment is held when the model asked for review, when its confidence is below
 * {@code mygrant.impact.review-confidence-threshold}, or when the independent judge did not pass
 * it (including when the judge was unavailable). Urgency plays no part: a confident, verified
 * HIGH result goes straight to the user.
 */
@Component
@EnableConfigurationProperties(ImpactAssessmentProperties.class)
public class ImpactReviewPolicy {

    /** Why an assessment was held, in the order a reviewer should read them. */
    public enum HoldReason {
        NOT_VERIFIED("Independent check did not pass"),
        LOW_CONFIDENCE("Low confidence"),
        MODEL_REQUESTED("Model asked for review");

        private final String label;

        HoldReason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final int confidenceThreshold;

    @Autowired
    public ImpactReviewPolicy(ImpactAssessmentProperties properties) {
        this(properties.reviewConfidenceThreshold());
    }

    public ImpactReviewPolicy(int confidenceThreshold) {
        if (confidenceThreshold < 0 || confidenceThreshold > 100) {
            throw new IllegalArgumentException("Confidence threshold must be between 0 and 100");
        }
        this.confidenceThreshold = confidenceThreshold;
    }

    /** True when a reviewer must see the assessment before its owner does. */
    public boolean requiresReview(ProfileImpactAssessment assessment) {
        return !holdReasons(assessment).isEmpty();
    }

    /** Empty when the assessment can be published; only completed assessments can be. */
    public List<HoldReason> holdReasons(ProfileImpactAssessment assessment) {
        if (assessment.status() != AssessmentStatus.COMPLETED) {
            throw new IllegalArgumentException("Only completed assessments can be published or held");
        }
        boolean verified = assessment.evaluation() != null
                && assessment.evaluation().verdict() == JudgeEvaluation.Verdict.YES;
        List<HoldReason> reasons = new ArrayList<>();
        if (!verified) reasons.add(HoldReason.NOT_VERIFIED);
        if (assessment.confidence() < confidenceThreshold) reasons.add(HoldReason.LOW_CONFIDENCE);
        // requiresHumanReview also carries "the judge did not pass it", already reported above.
        if (assessment.requiresHumanReview() && verified) reasons.add(HoldReason.MODEL_REQUESTED);
        return List.copyOf(reasons);
    }

    public int confidenceThreshold() {
        return confidenceThreshold;
    }
}
