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
        Double similarityScore
) {
}
