package mygrant.manager;

import java.security.Principal;
import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import mygrant.manager.dto.ManagerMemberResponse;

@RestController
@RequestMapping("/api/v1/manager/members")
@PreAuthorize("hasRole('MANAGER')")
@Tag(name = "Manager")
public class ManagerMembershipController {
    private final ManagerMembershipService service;

    public ManagerMembershipController(ManagerMembershipService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List the applicants linked to the signed-in manager")
    public List<ManagerMemberResponse> list(Principal principal) {
        return service.list(principal.getName());
    }

    @PostMapping("/{userId}")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Link an applicant to the signed-in manager")
    @ApiResponse(responseCode = "404", description = "The user is not an active applicant")
    @ApiResponse(responseCode = "409", description = "The applicant is already linked")
    public ManagerMemberResponse add(Principal principal, @PathVariable Long userId) {
        return service.add(principal.getName(), userId);
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Unlink an applicant from the signed-in manager")
    @ApiResponse(responseCode = "404", description = "The link does not exist")
    public void remove(Principal principal, @PathVariable Long userId) {
        service.remove(principal.getName(), userId);
    }
}
