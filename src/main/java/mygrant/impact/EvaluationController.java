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
    @ApiResponse(responseCode = "503", description = "Independent evaluation temporarily unavailable; try again later")
    @ApiResponse(responseCode = "429", description = "Too many re-evaluations in a short time; wait for the Retry-After seconds",
            content = @io.swagger.v3.oas.annotations.media.Content(schema =
                    @io.swagger.v3.oas.annotations.media.Schema(implementation = mygrant.common.ApiError.class)))
    public OutputPublicationService.Result evaluate(@Valid @RequestBody Request request, java.security.Principal principal) {
        rateLimiter.acquire(mygrant.common.RateLimiter.Allowance.EVALUATIONS, principal.getName());
        var result = service.evaluate(request.outputId());
        // CS203-174: re-evaluating is this endpoint's only job, so an unavailable judge is a 503.
        // The service's own transaction has already committed the audit entry and review state.
        if (result != null && result.evaluation() != null
                && result.evaluation().verdict() == JudgeEvaluation.Verdict.UNAVAILABLE) {
            throw new mygrant.common.AiUnavailableException();
        }
        return result;
    }
}
