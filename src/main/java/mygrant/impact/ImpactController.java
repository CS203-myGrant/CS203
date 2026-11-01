package mygrant.impact;

import java.security.Principal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** The signed-in user's published impact feed. */
@RestController
@RequestMapping("/api/v1/impacts")
@Tag(name = "Impacts")
public class ImpactController {
    private final ImpactFeedService service;

    public ImpactController(ImpactFeedService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List only your published impact assessments",
            description = "Defaults to current assessments and CRITICAL-first ordering. Sort by severity or date.")
    @ApiResponse(responseCode = "200", description = "A page of published assessments")
    @ApiResponse(responseCode = "400", description = "Invalid filter, sort or page size")
    public Page<ImpactFeedService.ImpactView> list(Principal principal,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "false") boolean archived) {
        return service.list(principal.getName(), pageable, severity, archived);
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

    public record ArchiveRequest(@NotNull Boolean archived) {}
}
