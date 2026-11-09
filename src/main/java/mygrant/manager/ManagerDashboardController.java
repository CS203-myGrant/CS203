package mygrant.manager;

import java.security.Principal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import mygrant.manager.dto.ManagerDashboardResponse;

@RestController
@RequestMapping("/api/v1/manager")
@PreAuthorize("hasRole('MANAGER')")
@Tag(name = "Manager")
public class ManagerDashboardController {
    private final ManagerDashboardService service;

    public ManagerDashboardController(ManagerDashboardService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Aggregate policy impact for the signed-in manager's linked applicants",
            description = "Counts active linked applicants by visa type and visible open assessments by severity, "
                    + "and returns pending reports for those applicants.")
    @ApiResponse(responseCode = "200", description = "The manager's scoped dashboard")
    public ManagerDashboardResponse dashboard(Principal principal) {
        return service.dashboard(principal.getName());
    }
}
