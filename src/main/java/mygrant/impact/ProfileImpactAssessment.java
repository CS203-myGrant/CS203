package mygrant.impact;

import java.util.List;

/** Structured profile-specific triage with availability separate from severity. */
public record ProfileImpactAssessment(
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true,
                description = "Profile impact; null when processing is unavailable") ImpactUrgency urgency,
        int confidence,
        String summary,
        List<String> reasons,
        List<String> recommendedActions,
        List<String> missingInformation,
        boolean requiresHumanReview,
        String provider,
        String model,
        JudgeEvaluation evaluation,
        int attempts,
        AssessmentStatus status) {

    public ProfileImpactAssessment {
        // Older successful snapshots predate the explicit processing-status field.
        if (status == null) status = urgency == null ? AssessmentStatus.UNAVAILABLE : AssessmentStatus.COMPLETED;
        if ((status == AssessmentStatus.COMPLETED) != (urgency != null)) {
            throw new IllegalArgumentException("Completed assessments require urgency; unavailable results have none");
        }
    }

    public ProfileImpactAssessment(ImpactUrgency urgency, int confidence, String summary,
            List<String> reasons, List<String> recommendedActions, List<String> missingInformation,
            boolean requiresHumanReview, String provider, String model, JudgeEvaluation evaluation, int attempts) {
        this(urgency, confidence, summary, reasons, recommendedActions, missingInformation,
                requiresHumanReview, provider, model, evaluation, attempts,
                urgency == null ? AssessmentStatus.UNAVAILABLE : AssessmentStatus.COMPLETED);
    }
}
