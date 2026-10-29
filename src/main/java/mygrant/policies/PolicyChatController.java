package mygrant.policies;

import java.security.Principal;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/policies")
@Tag(name="Policy questions")
public class PolicyChatController {
    private final PolicyChatService service;
    public PolicyChatController(PolicyChatService service) { this.service=service; }

    @PostMapping("/{id}/questions")
    @Operation(summary="Ask a follow-up question about a policy",description="Uses the stored source, your current profile and saved recommendation. Includes a source link and a human review indicator. History is limited to ten messages. Not legal advice.")
    @ApiResponse(responseCode="200",description="Answer grounded in the selected policy, or an explanation that evidence is missing")
    @ApiResponse(responseCode="400",description="Invalid policy id, empty or oversized question, or invalid conversation history")
    @ApiResponse(responseCode="404",description="Policy not found")
    @ApiResponse(responseCode="503",description="Policy assistant temporarily unavailable")
    public PolicyChatService.Answer ask(@PathVariable Long id,Principal principal,
            @Valid @RequestBody PolicyChatService.Question request) {
        return service.ask(id,principal.getName(),request);
    }
}
