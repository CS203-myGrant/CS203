package mygrant.rag.dto;

public record CitationResponse(
        String externalId,
        String title,
        String agency,
        String status,
        String sourceType,
        String publicationDate,
        String effectiveDate,
        String sourceUrl,
        Double similarityScore,
        @io.swagger.v3.oas.annotations.media.Schema(description = "Exact supplied evidence chunk ID. Null only for legacy source-level citations. [n] selects the nth chunk, even when multiple chunks share an externalId.")
        String chunkId
) {
    /** Legacy source-level records remain readable but have no verified chunk identity. */
    public CitationResponse(String externalId, String title, String agency, String status,
            String sourceType, String publicationDate, String effectiveDate,
            String sourceUrl, Double similarityScore) {
        this(externalId, title, agency, status, sourceType, publicationDate, effectiveDate,
                sourceUrl, similarityScore, null);
    }
}
