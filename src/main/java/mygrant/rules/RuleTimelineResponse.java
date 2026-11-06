package mygrant.rules;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import mygrant.common.Confidence;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;

@Schema(name = "RuleTimeline", description = "A policy rule's stage history, with the source of each step")
public record RuleTimelineResponse(
        @Schema(description = "The rule's id", example = "7")
        long ruleId,
        @Schema(description = "Title of the rule's first document")
        String title,
        @Schema(description = "The stage set by primary (Federal Register) sources, as stored: PROPOSED, FINAL, "
                + "WITHDRAWN, GUIDANCE or UNKNOWN. News never changes it, and it is never EFFECTIVE.", example = "FINAL")
        PolicyStatus primaryStage,
        @Schema(description = "The stage for the caller today: EFFECTIVE when primaryStage is FINAL and asOf is on "
                + "or after effectiveDate, otherwise primaryStage", example = "EFFECTIVE")
        PolicyStatus currentStage,
        @Schema(description = "The caller's date that currentStage is worked out for: their test-clock date when "
                + "one is set, otherwise today", example = "2026-11-06")
        LocalDate asOf,
        @Schema(description = "The final rule's effective date; null until a final rule sets one", example = "2026-11-01")
        LocalDate effectiveDate,
        @Schema(description = "True once news linked to the rule by an identifier reported litigation or a stay. "
                + "Shown alongside primaryStage, never instead of it.")
        boolean disputed,
        @Schema(description = "The news item that marked the rule disputed; null when it is not disputed")
        RuleSource disputedBy,
        @Schema(description = "Dockets and RINs of the rule's primary documents")
        List<RuleIdentifier> identifiers,
        @Schema(description = "Every document in the rule, oldest first, with how it was linked")
        List<Document> documents,
        @Schema(description = "Stage history, oldest first, by source publication date")
        List<Step> steps
) {

    @Schema(name = "RuleTimelineStep", description = "One step in a rule's history")
    public record Step(
            @Schema(description = "The source's publication date; for a derived step, the effective date",
                    example = "2026-07-17")
            LocalDate observedOn,
            @Schema(description = "When the step was recorded; null for a derived step")
            Instant recordedAt,
            @Schema(description = "True for the TOOK_EFFECT step, which is worked out from the caller's date and "
                    + "never stored")
            boolean derived,
            @Schema(description = "The primary stage after this step", example = "FINAL")
            PolicyStatus primaryStage,
            @Schema(description = "The primary stage after the step before it; null for the first step",
                    example = "PROPOSED")
            PolicyStatus previousPrimaryStage,
            @Schema(description = "The effective date after this step", example = "2026-09-15")
            LocalDate effectiveDate,
            @Schema(description = "The effective date after the step before it")
            LocalDate previousEffectiveDate,
            @Schema(description = "Only on an ADDITIONAL_FINAL_RULE step: that separate final rule's own effective "
                    + "date. effectiveDate stays the rule's. Null on every other step.", example = "2025-01-17")
            LocalDate ownEffectiveDate,
            @Schema(description = "Whether the rule is disputed after this step")
            boolean disputed,
            @Schema(description = "What moved against the step before: STAGE, EFFECTIVE_DATE, DISPUTED, the derived "
                    + "TOOK_EFFECT, or ADDITIONAL_FINAL_RULE for a separate final rule in the same rulemaking, which "
                    + "changes neither the stage nor the rule's effective date. A step can be empty when an older "
                    + "document arrived after a newer one and repeats what is already known.")
            List<RuleChange> changes,
            @Schema(description = "The document this step came from; for a derived step, the one that set the "
                    + "effective date")
            RuleSource source
    ) {
    }

    @Schema(name = "RuleTimelineDocument", description = "A document in the rule and how it was matched")
    public record Document(
            long policyId,
            String externalId,
            SourceType sourceType,
            String title,
            LocalDate publishedOn,
            @Schema(description = "NEW_RULE, DOCKET, RIN, DOCKET_AND_RIN, FR_DOCUMENT or TITLE")
            MatchMethod matchMethod,
            Confidence confidence,
            @Schema(description = "The identifiers or title score behind the match", example = "DOCKET ICEB-2025-0001; RIN 1653-AA95")
            String matchedOn,
            @Schema(description = "True when a person should check the match")
            boolean needsReview,
            @Schema(description = "Why the match needs checking; null when it does not")
            String reviewReason
    ) {
    }
}
