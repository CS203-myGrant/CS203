package mygrant.impact;

import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Admin")
@RestController
@RequestMapping("/api/v1/evaluate")
@PreAuthorize("hasRole('ADMIN')")
public class EvaluationController {
    private final OutputPublicationService service;
    private final mygrant.common.RateLimiter rateLimiter;
    public EvaluationController(OutputPublicationService service, mygrant.common.RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    public record Request(@NotNull UUID outputId) {}

    @PostMapping
    @Operation(summary = "Re-evaluate a stored assessment or answer",
            description = "outputId is the original evaluationId. Uses the stored candidate and evidence; human verdicts remain authoritative.")
    @ApiResponse(responseCode = "200", description = "Evaluation and effective publication state")
    @ApiResponse(responseCode = "400", description = "Missing or invalid outputId")
    @ApiResponse(responseCode = "403", description = "Admin role required")
    @ApiResponse(responseCode = "404", description = "Stored output not found or predates candidate retention")
    @ApiResponse(responseCode = "429", description = "Too many re-evaluations in a short time; wait for the Retry-After seconds",
            content = @io.swagger.v3.oas.annotations.media.Content(schema =
                    @io.swagger.v3.oas.annotations.media.Schema(implementation = mygrant.common.ApiError.class)))
    public OutputPublicationService.Result evaluate(@Valid @RequestBody Request request, java.security.Principal principal) {
        rateLimiter.acquire(mygrant.common.RateLimiter.Allowance.EVALUATIONS, principal.getName());
        return service.evaluate(request.outputId());
    }
}
