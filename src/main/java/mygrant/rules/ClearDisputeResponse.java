package mygrant.rules;

import java.time.Instant;
import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import mygrant.policies.PolicyStatus;

@Schema(name = "RuleDisputeCleared", description = "The history step an admin's clearing wrote, and the rule after it")
public record ClearDisputeResponse(
        @Schema(description = "The rule's id", example = "3")
        long ruleId,
        @Schema(description = "The id of the history step that records the clearing", example = "12")
        long historyId,
        @Schema(description = "Unchanged by clearing", example = "FINAL")
        PolicyStatus primaryStage,
        @Schema(description = "Unchanged by clearing", example = "2026-09-15")
        LocalDate effectiveDate,
        @Schema(description = "Always false after clearing")
        boolean disputed,
        @Schema(description = "The admin who cleared it", example = "admin@example.com")
        String clearedBy,
        @Schema(description = "When the clearing was recorded")
        Instant clearedAt,
        String reason,
        @Schema(description = "The court order or news item that lifted the block; policyId is null")
        RuleSource source
) {
}
