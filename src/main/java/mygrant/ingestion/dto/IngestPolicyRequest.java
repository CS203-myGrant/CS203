package mygrant.ingestion.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;
import mygrant.policies.RestrictionCategory;

/** Validated policy metadata and source text accepted by manual ingestion. */
public record IngestPolicyRequest(
        @NotBlank @Size(max = 255) String externalId,
        @NotBlank @Size(max = 1000) String title,
        @NotBlank @Size(max = 255) String agency,
        @NotBlank @Size(max = 50) String visaType,
        @NotNull PolicyStatus status,
        @NotNull SourceType sourceType,
        @NotNull LocalDate publicationDate,
        LocalDate effectiveDate,
        @NotBlank @Size(max = 2048) String sourceUrl,
        @NotBlank String content,
        Set<String> affectedCountries,
        Set<RestrictionCategory> restrictionCategories,
        List<@NotBlank @Size(max = 2048) String> sourceReferences
) {
    public IngestPolicyRequest(String externalId, String title, String agency, String visaType,
            PolicyStatus status, SourceType sourceType, LocalDate publicationDate,
            LocalDate effectiveDate, String sourceUrl, String content) {
        this(externalId, title, agency, visaType, status, sourceType, publicationDate,
                effectiveDate, sourceUrl, content, Set.of(), Set.of(), List.of());
    }
}
