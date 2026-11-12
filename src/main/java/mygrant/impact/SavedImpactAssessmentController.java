package mygrant.impact;

import java.security.Principal;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;
import mygrant.sources.FreshnessPage;
import mygrant.common.RateLimiter;

@RestController
@RequestMapping("/api/v1/impact-assessments")
@Tag(name = "Impacts")
public class SavedImpactAssessmentController {
    private final SavedImpactAssessmentService service;
    private final IngestionAssessmentService requests;
    private final RateLimiter rateLimiter;

    public SavedImpactAssessmentController(SavedImpactAssessmentService service,
            IngestionAssessmentService requests, RateLimiter rateLimiter) {
        this.service = service;
        this.requests = requests;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/policy/{policyId}/request")
    @Operation(summary = "Request an assessment of a matching official policy for your profile",
            description = "For applicants who joined after a policy was imported. Reuses the saved result for "
                    + "this profile version. Pending review and unavailable output stay hidden from GET responses.")
    @ApiResponse(responseCode = "200", description = "Request processed; refresh the visible feed")
    @ApiResponse(responseCode = "400", description = "Incomplete profile or policy does not match")
    @ApiResponse(responseCode = "429", description = "Assessment request limit reached")
    public Map<String, String> request(Principal principal, @PathVariable Long policyId) {
        rateLimiter.acquire(RateLimiter.Allowance.RECOMMENDATIONS, principal.getName());
        requests.requestForProfile(policyId, principal.getName());
        return Map.of("message", "Assessment request processed. It will appear after any required review.");
    }

    @GetMapping
    @Operation(summary = "List your visible saved ingestion assessments",
            description = "Excludes pending, rejected and unavailable results. 20 per page by default; maximum 100. "
                    + "Ordered HIGH, MEDIUM, LOW, NOT_APPLICABLE, then newest first. Ordering is fixed. Optionally filter by policyId. "
                    + "CS203-175: the page's sourceFreshness covers every enabled policy source, even when the page is empty; "
                    + "each item's covers the sources matching its policy URL.")
    @ApiResponse(responseCode = "200", description = "A page of your visible assessments")
    @ApiResponse(responseCode = "400", description = "Invalid page size or urgency")
    public FreshnessPage<SavedImpactAssessmentResponse> list(Principal principal,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable,
            @RequestParam(required = false) ImpactUrgency urgency,
            @RequestParam(required = false) Long policyId) {
        return service.list(principal.getName(), pageable, urgency, policyId);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one of your visible saved ingestion assessments",
            description = "Includes sourceFreshness for the sources matching the policy URL (CS203-175).")
    @ApiResponse(responseCode = "200", description = "Your assessment and recommended actions")
    @ApiResponse(responseCode = "404", description = "Missing, hidden or owned by another user")
    public SavedImpactAssessmentResponse get(Principal principal, @PathVariable Long id) {
        return service.get(principal.getName(), id);
    }
}
