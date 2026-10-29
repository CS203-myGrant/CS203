package mygrant.rag;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import mygrant.rag.dto.RagQueryRequest;
import mygrant.rag.dto.RagQueryResponse;

@RestController
@RequestMapping("/api/v1/rag")
@Tag(name = "Policy questions")
public class RagController {

    private final RagService ragService;
    private final mygrant.policies.PolicyQuestionHistory history;

    public RagController(RagService ragService,mygrant.policies.PolicyQuestionHistory history) {
        this.ragService = ragService;
        this.history=history;
    }

    @PostMapping("/query")
    @Operation(summary = "Ask a question about indexed policies",
            description = "Retrieves evidence for the signed-in caller's saved visa category and answers from it with "
                    + "citations. With no matching evidence it says so instead of guessing. Not legal advice; "
                    + "Generated answers must pass independent review; check `evaluation`, `confidence`, `requiresReview` and `warnings`.")
    @ApiResponse(responseCode = "200", description = "The answer and its evidence")
    @ApiResponse(responseCode = "400", description = "The question is missing or too long")
    @ApiResponse(responseCode = "503", description = "The embedding or AI service is unavailable")
    public RagQueryResponse query(@Valid @RequestBody RagQueryRequest request,java.security.Principal principal) {
        return ragService.queryForUser(principal.getName(),request.question());
    }

    @org.springframework.web.bind.annotation.GetMapping("/history")
    @Operation(summary="Read your policy Q&A history",description="Signed-in user's general policy questions and answers, oldest first.")
    @ApiResponse(responseCode="200",description="A page of saved questions, answers, citations and confidence")
    public org.springframework.data.domain.Page<mygrant.policies.PolicyQuestionHistory.Entry> history(java.security.Principal principal,
            @org.springdoc.core.annotations.ParameterObject @org.springframework.data.web.PageableDefault(size=20) org.springframework.data.domain.Pageable pageable){
        return history.list(principal.getName(),null,pageable);
    }
}
