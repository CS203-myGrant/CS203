package mygrant.impact;

import java.util.List;

/** Structured, profile-specific policy triage returned to the RAG Inspector. */
public record ProfileImpactAssessment(
        ImpactUrgency urgency,
        int confidence,
        String summary,
        List<String> reasons,
        List<String> recommendedActions,
        boolean requiresHumanReview,
        String provider,
        String model,
        JudgeEvaluation evaluation,
        int attempts) {
}
