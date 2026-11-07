package mygrant.ingestion.federalregister;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Federal Register API fields required for policy ingestion. {@code docket_ids} holds labelled
 * dockets ("DHS Docket No. ICEB-2025-0001") and {@code regulation_id_numbers} bare RINs
 * ("1653-AA95"); both tie a document to its rule (CS203-86). {@code action} says what the
 * document does, such as "Final rule." or "Proposed rule; withdrawal."
 */
public record FederalRegisterDocument(
        @JsonProperty("document_number") String documentNumber,
        String title,
        String type,
        @JsonProperty("publication_date") LocalDate publicationDate,
        @JsonProperty("effective_on") LocalDate effectiveDate,
        @JsonProperty("html_url") String htmlUrl,
        @JsonProperty("raw_text_url") String rawTextUrl,
        List<Agency> agencies,
        String action,
        @JsonProperty("docket_ids") List<String> docketIds,
        @JsonProperty("regulation_id_numbers") List<String> regulationIdNumbers
) {
    public FederalRegisterDocument {
        docketIds = docketIds == null ? List.of() : List.copyOf(docketIds);
        regulationIdNumbers = regulationIdNumbers == null ? List.of() : List.copyOf(regulationIdNumbers);
    }

    /** A document without an action or rule identifiers. */
    public FederalRegisterDocument(String documentNumber, String title, String type, LocalDate publicationDate,
            LocalDate effectiveDate, String htmlUrl, String rawTextUrl, List<Agency> agencies) {
        this(documentNumber, title, type, publicationDate, effectiveDate, htmlUrl, rawTextUrl, agencies,
                null, List.of(), List.of());
    }

    /** Issuing agency metadata from the Federal Register API. */
    public record Agency(String name, String slug) {
    }
}
