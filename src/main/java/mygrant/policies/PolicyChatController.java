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
    private final PolicyQuestionHistory history;
    public PolicyChatController(PolicyChatService service,PolicyQuestionHistory history) { this.service=service;this.history=history; }

    @PostMapping("/{id}/questions")
    @Operation(summary="Ask a follow-up question about a policy",description="Retrieves evidence filtered by your saved visa category and this policy. Uses your current profile, saved recommendation and last five saved exchanges. Client-supplied history is ignored. Approved answers include citations, evidence confidence and independent evaluation. Saves the returned response to your history. Not legal advice.")
    @ApiResponse(responseCode="200",description="Answer grounded in the selected policy, or an explanation that evidence is missing")
    @ApiResponse(responseCode="400",description="Invalid policy id, empty or oversized question, or invalid conversation history")
    @ApiResponse(responseCode="404",description="Policy not found")
    @ApiResponse(responseCode="503",description="Policy assistant temporarily unavailable")
    public PolicyChatService.Answer ask(@PathVariable Long id,Principal principal,
            @Valid @RequestBody PolicyChatService.Question request) {
        var answer=service.ask(id,principal.getName(),request);
        history.record(principal.getName(),id,request.question(),answer);
        return answer;
    }

    @GetMapping("/{id}/questions")
    @Operation(summary="Read your saved questions for a policy",description="Only the signed-in user's history, oldest first. Client history is never used as model context.")
    @ApiResponse(responseCode="200",description="A page of saved questions and answers")
    public org.springframework.data.domain.Page<PolicyQuestionHistory.Entry> history(@PathVariable Long id,Principal principal,
            @org.springdoc.core.annotations.ParameterObject @org.springframework.data.web.PageableDefault(size=20) org.springframework.data.domain.Pageable pageable){
        return history.list(principal.getName(),id,pageable);
    }
}
