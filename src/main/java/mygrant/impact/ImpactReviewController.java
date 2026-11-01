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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
    @Operation(summary = "List pending impact drafts for a reviewer")
    @ApiResponse(responseCode = "200", description = "Up to 100 pending drafts")
    public List<ImpactReviewService.PendingImpact> pending() {
        return service.pending();
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Publish a human-corrected impact assessment")
    @ApiResponse(responseCode = "200", description = "Published correction and new version")
    @ApiResponse(responseCode = "400", description = "Invalid correction details")
    @ApiResponse(responseCode = "404", description = "Assessment not found")
    public ImpactReviewService.ReviewResult publish(@PathVariable long id, Principal principal,
            @Valid @RequestBody Correction correction) {
        return service.publishCorrection(id, principal.getName(), correction);
    }

    public record Correction(@NotBlank String severity, @Min(0) @Max(100) int confidence,
            @NotBlank @Size(max = 1500) String summary,
            @NotNull List<@NotBlank String> analysis,
            @NotNull List<@NotBlank String> actionItems,
            @NotNull List<@NotBlank String> missingInformation) {}
}
