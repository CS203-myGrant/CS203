package mygrant.impact;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import mygrant.sources.SourceFreshnessService.Freshness;

/** Owner-facing assessment plus source metadata, without loading the source document body. */
public record SavedImpactAssessmentResponse(
        Long id, Long userId, Long policyId, String policyTitle, String sourceUrl,
        int profileVersion, AssessmentStatus status, ImpactUrgency urgency, Integer confidence,
        String summary, List<String> reasons, List<String> recommendedActions,
        boolean requiresReview, AssessmentReviewStatus reviewStatus, boolean reviewedByHuman,
        String provider, String model, Instant createdAt,
        @Schema(description = "CS203-175: freshness of the enabled sources whose host matches this policy's source URL")
        Freshness sourceFreshness) {

    /** Used by the repository's JPQL query; the service adds the source freshness afterwards. */
    public SavedImpactAssessmentResponse(ImpactAssessment assessment, String policyTitle, String sourceUrl) {
        this(assessment.getId(), assessment.getUserId(), assessment.getPolicyId(), policyTitle, sourceUrl,
                assessment.getProfileVersion(), assessment.getStatus(), assessment.getUrgency(), assessment.getConfidence(),
                assessment.getSummary(), assessment.getReasons(), assessment.getRecommendedActions(),
                assessment.isRequiresReview(), assessment.getReviewStatus(),
                assessment.getReviewStatus() == AssessmentReviewStatus.APPROVED,
                assessment.getProvider(), assessment.getModel(), assessment.getCreatedAt(), null);
    }

    SavedImpactAssessmentResponse withSourceFreshness(Freshness freshness) {
        return new SavedImpactAssessmentResponse(id, userId, policyId, policyTitle, sourceUrl, profileVersion,
                status, urgency, confidence, summary, reasons, recommendedActions, requiresReview, reviewStatus,
                reviewedByHuman, provider, model, createdAt, freshness);
    }
}
