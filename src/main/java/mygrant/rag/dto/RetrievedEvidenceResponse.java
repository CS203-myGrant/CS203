package mygrant.rag.dto;

public record RetrievedEvidenceResponse(
        int citationNumber,
        String externalId,
        String title,
        String agency,
        String status,
        String sourceType,
        String sourceUrl,
        Double similarityScore,
        String snippet
) {
}
