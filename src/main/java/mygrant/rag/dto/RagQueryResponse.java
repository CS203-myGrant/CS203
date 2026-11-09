package mygrant.rag.dto;

import java.util.List;

import mygrant.common.Confidence;

public record RagQueryResponse(
        String answer,
        Confidence confidence,
        boolean requiresReview,
        List<CitationResponse> citations,
        List<RetrievedEvidenceResponse> retrievedEvidence,
        List<String> warnings,
        mygrant.impact.JudgeEvaluation evaluation
) {
}
