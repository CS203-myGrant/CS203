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

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/query")
    @Operation(summary = "Ask a question about indexed policies",
            description = "Retrieves evidence for the visa type (F-1, J-1 or H-1B) and answers from it with "
                    + "citations. With no matching evidence it says so instead of guessing. Not legal advice; "
                    + "Generated answers must pass independent review; check `evaluation`, `confidence`, `requiresReview` and `warnings`.")
    @ApiResponse(responseCode = "200", description = "The answer and its evidence")
    @ApiResponse(responseCode = "400", description = "The question or visa type is missing, too long or unsupported")
    @ApiResponse(responseCode = "503", description = "The embedding or AI service is unavailable")
    public RagQueryResponse query(@Valid @RequestBody RagQueryRequest request) {
        return ragService.query(request);
    }
}
