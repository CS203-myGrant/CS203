package mygrant.impact;

import java.security.Principal;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import mygrant.policies.RestrictionScope;

/** Reviewer-only access to pending drafts and corrected publication. */
@RestController
@RequestMapping("/api/v1/review/impacts")
@PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
@Tag(name = "Impact review")
public class ImpactReviewController {
    private final ImpactReviewService service;

    public ImpactReviewController(ImpactReviewService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List pending assessment and answer drafts for a reviewer")
    @ApiResponse(responseCode = "200", description = "Up to 100 pending drafts")
    public List<ImpactReviewService.PendingImpact> pending(Principal principal) {
        return service.pending(principal.getName());
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Publish a human-corrected assessment or answer",
            description = "For Policy Q&A drafts, summary supplies the corrected answer. Existing manager membership scope applies.")
    @ApiResponse(responseCode = "200", description = "Published correction and new version")
    @ApiResponse(responseCode = "400", description = "Invalid correction details, or, for a reported assessment, "
            + "a missing review note or a correction that changes nothing")
    @ApiResponse(responseCode = "404", description = "Assessment not found")
    public ImpactReviewService.ReviewResult publish(@PathVariable long id, Principal principal,
            @Valid @RequestBody Correction correction) {
        return service.publishCorrection(id, principal.getName(), correction);
    }

    @PostMapping("/{id}/reports/dismiss")
    @Operation(summary = "Dismiss applicant reports on an assessment that is correct as published",
            description = "Closes the open reports without changing the assessment. To fix the assessment, "
                    + "publish a correction instead, which also resolves the reports.")
    @ApiResponse(responseCode = "200", description = "How many open reports were dismissed")
    @ApiResponse(responseCode = "400", description = "The explanation to the applicant is missing")
    @ApiResponse(responseCode = "404", description = "Assessment not found, outside your scope, or without open reports")
    public ImpactReviewService.ReportDismissal dismissReports(@PathVariable long id, Principal principal,
            @Valid @RequestBody Dismissal dismissal) {
        return service.dismissReports(id, principal.getName(), dismissal.note());
    }

    @GetMapping("/reports/count")
    @Operation(summary = "Count open applicant reports in your review queue",
            description = "For the queue badge. Managers count only their linked applicants' reports.")
    @ApiResponse(responseCode = "200", description = "Open reports and how many assessments they are on")
    public RecommendationReportService.OpenCount openReportCount(Principal principal) {
        return service.openReportCount(principal.getName());
    }

    public record Dismissal(
            @io.swagger.v3.oas.annotations.media.Schema(description = "Why the assessment is correct; shown to the applicant")
            @NotBlank @Size(max = 1000) String note) {}

    public record Correction(@NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "HIGH|MEDIUM|LOW|NOT_APPLICABLE")
            @io.swagger.v3.oas.annotations.media.Schema(implementation = ImpactUrgency.class) String severity, @Min(0) @Max(100) int confidence,
            @NotBlank @Size(max = 1500) String summary,
            @NotNull List<@NotBlank String> analysis,
            @NotNull List<@NotBlank String> actionItems,
            @NotNull List<@NotBlank String> missingInformation,
            List<@Valid CountryRuleCorrection> countryRestrictions,
            @io.swagger.v3.oas.annotations.media.Schema(description = "Required when the assessment has open "
                    + "applicant reports: what was corrected and why. Shown to the applicant.")
            @Size(max = 1000) String reviewNote) {
        public Correction(String severity, int confidence, String summary, List<String> analysis,
                List<String> actionItems, List<String> missingInformation) {
            this(severity, confidence, summary, analysis, actionItems, missingInformation, null, null);
        }
        public Correction(String severity, int confidence, String summary, List<String> analysis,
                List<String> actionItems, List<String> missingInformation,
                List<CountryRuleCorrection> countryRestrictions) {
            this(severity, confidence, summary, analysis, actionItems, missingInformation, countryRestrictions, null);
        }
    }
    public record CountryRuleCorrection(@NotBlank String countryCode, @NotNull RestrictionScope scope) {}
}
