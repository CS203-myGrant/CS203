package mygrant.impact;

import java.security.Principal;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The signed-in user's published impact feed. */
@RestController
@RequestMapping("/api/v1/impacts")
@Tag(name = "Impacts")
public class ImpactController {
    private final ImpactFeedService service;
    private final RecommendationReportService reports;

    public ImpactController(ImpactFeedService service, RecommendationReportService reports) {
        this.service = service;
        this.reports = reports;
    }

    @GetMapping
    @Operation(summary = "List only your published impact assessments",
            description = "Defaults to current assessments and HIGH-first ordering. Sort by severity or date.")
    @ApiResponse(responseCode = "200", description = "A page of published assessments")
    @ApiResponse(responseCode = "400", description = "Invalid filter, sort or page size")
    public Page<ImpactFeedService.ImpactView> list(Principal principal,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable,
            @io.swagger.v3.oas.annotations.Parameter(schema =
                    @io.swagger.v3.oas.annotations.media.Schema(implementation = ImpactUrgency.class))
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "false") boolean archived) {
        return service.list(principal.getName(), pageable, severity, archived);
    }

    @GetMapping("/consolidated")
    @Operation(summary = "Get one consolidated view of your current policy assessments",
            description = "Combines net urgency, deduplicated citations and uncertainty notes while preserving "
                    + "each policy-specific assessment in the breakdown.")
    @ApiResponse(responseCode = "200", description = "Consolidated published assessments")
    public ImpactFeedService.ConsolidatedAssessment consolidated(Principal principal) {
        return service.consolidated(principal.getName());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one of your published impact assessments")
    @ApiResponse(responseCode = "200", description = "Analysis, citations, actions and review marker")
    @ApiResponse(responseCode = "404", description = "Missing, unpublished or owned by another user")
    public ImpactFeedService.ImpactView get(Principal principal, @PathVariable long id) {
        return service.get(principal.getName(), id);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Archive or unarchive one of your published assessments")
    @ApiResponse(responseCode = "200", description = "The assessment with its new archive state")
    @ApiResponse(responseCode = "400", description = "archived must be true or false")
    @ApiResponse(responseCode = "404", description = "Missing, unpublished or owned by another user")
    public ImpactFeedService.ImpactView archive(Principal principal, @PathVariable long id,
            @Valid @RequestBody ArchiveRequest request) {
        return service.archive(principal.getName(), id, request.archived());
    }

    @PostMapping("/{id}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Report a problem with one of your published assessments",
            description = "Records the category, your description and the assessment version you saw, and sends "
                    + "the assessment to the reviewer queue. The assessment stays visible until a reviewer "
                    + "publishes a correction or dismisses the report.")
    @ApiResponse(responseCode = "201", description = "The report, now open for review")
    @ApiResponse(responseCode = "400", description = "Missing or unknown category, or empty or oversized details")
    @ApiResponse(responseCode = "404", description = "Missing, unpublished or owned by another user")
    @ApiResponse(responseCode = "409", description = "You already have an open report on it, or it changed since you loaded it")
    public RecommendationReportService.Report report(Principal principal, @PathVariable long id,
            @Valid @RequestBody ReportRequest request) {
        return reports.report(principal.getName(), id, request.category(), request.details(), request.version());
    }

    @GetMapping("/{id}/reports")
    @Operation(summary = "List your reports on one of your published assessments", description = "Newest first.")
    @ApiResponse(responseCode = "200", description = "Your reports and their review status")
    @ApiResponse(responseCode = "404", description = "Missing, unpublished or owned by another user")
    public List<RecommendationReportService.Report> reports(Principal principal, @PathVariable long id) {
        return reports.mine(principal.getName(), id);
    }

    public record ArchiveRequest(@NotNull Boolean archived) {}

    public record ReportRequest(
            @NotNull ReportCategory category,
            @NotBlank @Size(max = 900) String details,
            @io.swagger.v3.oas.annotations.media.Schema(description = "The assessment version being reported, from its detail view")
            @NotNull @Min(1) Integer version) {}
}
