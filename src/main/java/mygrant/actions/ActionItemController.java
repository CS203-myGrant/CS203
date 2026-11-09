package mygrant.actions;

import java.security.Principal;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

/** The signed-in user's checklist of steps from their published impact assessments. */
@RestController
@RequestMapping("/api/v1/actions")
@Tag(name = "Actions")
public class ActionItemController {
    private final ActionItemService service;

    public ActionItemController(ActionItemService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List your checklist items",
            description = "Only your own items, from assessments you can see in the impact feed. Without a "
                    + "status filter, returns PENDING and IN_PROGRESS items of unarchived assessments. Ordered "
                    + "by deadline (undated last), then priority (HIGH first), then assessment and position; "
                    + "a sort parameter is ignored. 50 per page by default, at most 100.")
    @ApiResponse(responseCode = "200", description = "A page of checklist items")
    @ApiResponse(responseCode = "400", description = "Unknown status or priority, or a page size outside 1 to 100")
    public Page<ActionItemView> list(Principal principal,
            @ParameterObject @PageableDefault(size = 50) Pageable pageable,
            @Parameter(description = "Only these statuses; repeat for more than one")
            @RequestParam(required = false) List<ActionStatus> status,
            @Parameter(description = "Only these priorities; repeat for more than one")
            @RequestParam(required = false) List<ActionPriority> priority) {
        return service.list(principal.getName(), pageable, status, priority);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Change the status of one of your checklist items",
            description = "Any status can move to any other. COMPLETED stamps completedAt and DISMISSED stamps "
                    + "dismissedAt; leaving either clears its stamp. Sending the current status changes nothing.")
    @ApiResponse(responseCode = "200", description = "The item with its new status")
    @ApiResponse(responseCode = "400", description = "status is missing or not PENDING, IN_PROGRESS, COMPLETED or DISMISSED")
    @ApiResponse(responseCode = "403", description = "The item belongs to another user, or onboarding is unfinished")
    @ApiResponse(responseCode = "404", description = "No such item, or its assessment is not published to its owner")
    public ActionItemView update(Principal principal, @PathVariable long id,
            @Valid @RequestBody StatusRequest request) {
        return service.updateStatus(principal.getName(), id, request.status());
    }

    public record StatusRequest(@NotNull ActionStatus status) {}
}
