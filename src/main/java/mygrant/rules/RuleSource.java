package mygrant.rules;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import mygrant.policies.PolicyDocument;
import mygrant.policies.SourceType;

/** Where a history entry came from. policyId is null once the document has been deleted. */
@Schema(name = "RuleSource", description = "The document a step came from, copied when the step was recorded")
public record RuleSource(
        @Schema(description = "The policy document's id; null once the document has been deleted", example = "41")
        Long policyId,
        @Schema(description = "Federal Register document number, or the news item's id", example = "2026-14439")
        String externalId,
        @Schema(description = "PRIMARY for the Federal Register, SECONDARY for news")
        SourceType sourceType,
        @Schema(example = "https://www.federalregister.gov/d/2026-14439")
        String url,
        String title,
        @Schema(example = "Homeland Security Department")
        String agency,
        @Schema(example = "2026-07-17")
        LocalDate publishedOn) {

    public static RuleSource of(PolicyDocument document) {
        return new RuleSource(document.getId(), document.getExternalId(), document.getSourceType(),
                document.getSourceUrl(), document.getTitle(), document.getAgency(), document.getPublicationDate());
    }
}
