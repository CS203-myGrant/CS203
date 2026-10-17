package mygrant.ingestion.federalregister;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Federal Register API fields required for policy ingestion. */
public record FederalRegisterDocument(
        @JsonProperty("document_number") String documentNumber,
        String title,
        String type,
        @JsonProperty("publication_date") LocalDate publicationDate,
        @JsonProperty("effective_on") LocalDate effectiveDate,
        @JsonProperty("html_url") String htmlUrl,
        @JsonProperty("raw_text_url") String rawTextUrl,
        List<Agency> agencies
) {
    /** Issuing agency metadata from the Federal Register API. */
    public record Agency(String name, String slug) {
    }
}
