package mygrant.rules;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Why an admin clears a rule's dispute, and the court order or news item that lifted the block. */
@Schema(name = "ClearRuleDispute",
        description = "Why the dispute is cleared, and the court order or news item that lifted the block")
public record ClearDisputeRequest(
        @Schema(description = "Why the block no longer applies, in your own words",
                example = "The First Circuit vacated the postponement; DHS says the rule now applies.")
        @NotBlank @Size(max = 2000) String reason,
        @NotNull @Valid Source source
) {

    /**
     * The source that lifted the block, in the same fields as a court order recorded with
     * POST /api/v1/ingestion/documents.
     */
    @Schema(name = "ClearRuleDisputeSource", description = "The court order or news item that lifted the block. "
            + "Same fields as a court order recorded with POST /api/v1/ingestion/documents.")
    public record Source(
            @Schema(description = "Your id for the order or article, such as the case number and date",
                    example = "ca1-26-1702-2026-11-02")
            @NotBlank @Size(max = 255) String externalId,
            @Schema(example = "Appeals court lifts the postponement of the duration of status rule")
            @NotBlank @Size(max = 1000) String title,
            @Schema(description = "The court, or the publisher of the report",
                    example = "U.S. Court of Appeals for the First Circuit")
            @NotBlank @Size(max = 255) String agency,
            @Schema(description = "Where the order or a report of it can be read",
                    example = "https://www.ca1.uscourts.gov/opinions/26-1702")
            @NotBlank @Size(max = 2048) @Pattern(regexp = "(?i)https?://\\S+", message = "must be an http or https URL")
            String sourceUrl,
            @Schema(description = "The date of the order. Not before the report that disputed the rule, and not "
                    + "after your date on the test clock.", example = "2026-11-02")
            @NotNull LocalDate publicationDate
    ) {
    }
}
