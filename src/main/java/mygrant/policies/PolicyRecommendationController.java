package mygrant.policies;

import java.security.Principal;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import mygrant.common.AiUnavailableException;
import mygrant.common.ApiError;
import mygrant.impact.AssessmentStatus;
import mygrant.impact.ProfileImpactAssessment;
import mygrant.impact.ProfileImpactAssessmentService;

/** Generates recommendations from a stored policy and the authenticated user's profile. */
@RestController
@RequestMapping("/api/v1/policies")
@Tag(name = "Policies")
public class PolicyRecommendationController {
    private final PolicyService policies;
    private final ProfileImpactAssessmentService assessments;
    private final PolicyRecommendationStore store;

    public PolicyRecommendationController(PolicyService policies, ProfileImpactAssessmentService assessments,
            PolicyRecommendationStore store) {
        this.policies = policies;
        this.assessments = assessments;
        this.store = store;
    }

    @PostMapping("/{id}/recommendation")
    @Operation(summary = "Assess a stored policy for your profile",
            description = "Uses the signed-in user's profile and stored source text to explain impact and next steps. "
                    + "Set refresh=true only when the applicant explicitly requests a new assessment.")
    @ApiResponse(responseCode = "200", description = "Personalized assessment, including availability and review status")
    @ApiResponse(responseCode = "400", description = "The id is not a number")
    @ApiResponse(responseCode = "404", description = "No policy has this id")
    @ApiResponse(responseCode = "503", description = "The AI service failed after its retries; try again later "
            + "(Retry-After header)", content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ProfileImpactAssessment recommendation(@PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean refresh, Principal principal) {
        PolicyDocument policy = policies.findById(id);
        ProfileImpactAssessment result = store.getOrGenerate(principal.getName(), policy,
                () -> assessments.assess(principal.getName(), policy, PolicyAssessmentInputs.document(policy),
                        PolicyAssessmentInputs.classification(policy)), refresh);
        // CS203-174: nothing was stored, so the user can simply try again later.
        if (result != null && result.status() == AssessmentStatus.UNAVAILABLE) throw new AiUnavailableException();
        return result;
    }
}
