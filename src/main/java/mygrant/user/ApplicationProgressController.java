package mygrant.user;

import java.security.Principal;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/profiles/me/application-progress")
@Tag(name = "Profile")
public class ApplicationProgressController {
    private final ApplicationProgressService service;

    public ApplicationProgressController(ApplicationProgressService service) { this.service = service; }

    @GetMapping
    @Operation(summary = "Get your visa application tracker", description = "Self-reported progress, not embassy status.")
    @ApiResponse(responseCode = "200", description = "Saved application or a new empty tracker")
    public ApplicationProgressService.Progress get(Principal principal) { return service.get(principal.getName()); }

    @PutMapping
    @Operation(summary = "Save your visa application tracker", description = "Replaces your current application details and completed steps.")
    @ApiResponse(responseCode = "200", description = "Saved application progress")
    @ApiResponse(responseCode = "400", description = "Invalid details or step numbers")
    public ApplicationProgressService.Progress save(Principal principal,
            @Valid @RequestBody ApplicationProgressService.Details details) {
        return service.save(principal.getName(), details);
    }
}
