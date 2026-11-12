package mygrant.impact;

import java.time.Instant;
import java.util.List;

/** Owner-facing assessment plus source metadata, without loading the source document body. */
public record SavedImpactAssessmentResponse(
        Long id, Long userId, Long policyId, String policyTitle, String sourceUrl,
        int profileVersion, AssessmentStatus status, ImpactUrgency urgency, Integer confidence,
        String summary, List<String> reasons, List<String> recommendedActions,
        boolean requiresReview, AssessmentReviewStatus reviewStatus, boolean reviewedByHuman,
        String provider, String model, Instant createdAt) {

    public SavedImpactAssessmentResponse(ImpactAssessment assessment, String policyTitle, String sourceUrl) {
        this(assessment.getId(), assessment.getUserId(), assessment.getPolicyId(), policyTitle, sourceUrl,
                assessment.getProfileVersion(), assessment.getStatus(), assessment.getUrgency(), assessment.getConfidence(),
                assessment.getSummary(), assessment.getReasons(), assessment.getRecommendedActions(),
                assessment.isRequiresReview(), assessment.getReviewStatus(),
                assessment.getReviewStatus() == AssessmentReviewStatus.APPROVED,
                assessment.getProvider(), assessment.getModel(), assessment.getCreatedAt());
    }
}
