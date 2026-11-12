package mygrant.impact;

import java.security.Principal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/impact-assessments")
@Tag(name = "Impacts")
public class SavedImpactAssessmentController {
    private final SavedImpactAssessmentService service;

    public SavedImpactAssessmentController(SavedImpactAssessmentService service) { this.service = service; }

    @GetMapping
    @Operation(summary = "List your visible saved ingestion assessments",
            description = "Excludes pending, rejected and unavailable results. 20 per page by default; maximum 100. "
                    + "Ordered HIGH, MEDIUM, LOW, NOT_APPLICABLE, then newest first. Ordering is fixed. Optionally filter by policyId.")
    @ApiResponse(responseCode = "200", description = "A page of your visible assessments")
    @ApiResponse(responseCode = "400", description = "Invalid page size or urgency")
    public Page<SavedImpactAssessmentResponse> list(Principal principal,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable,
            @RequestParam(required = false) ImpactUrgency urgency,
            @RequestParam(required = false) Long policyId) {
        return service.list(principal.getName(), pageable, urgency, policyId);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one of your visible saved ingestion assessments")
    @ApiResponse(responseCode = "200", description = "Your assessment and recommended actions")
    @ApiResponse(responseCode = "404", description = "Missing, hidden or owned by another user")
    public SavedImpactAssessmentResponse get(Principal principal, @PathVariable Long id) {
        return service.get(principal.getName(), id);
    }
}
