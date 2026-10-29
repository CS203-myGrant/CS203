package mygrant.policies;

import java.security.Principal;
import java.util.Arrays;
import java.util.List;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import mygrant.impact.ProfileImpactAssessment;
import mygrant.impact.ProfileImpactAssessmentService;
import mygrant.ingestion.classification.VisaClassification;
import mygrant.ingestion.federalregister.FederalRegisterDocument;

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
            description = "Uses the signed-in user's profile and stored source text to explain impact and next steps.")
    @ApiResponse(responseCode = "200", description = "Personalized assessment, including availability and review status")
    @ApiResponse(responseCode = "400", description = "The id is not a number")
    @ApiResponse(responseCode = "404", description = "No policy has this id")
    public ProfileImpactAssessment recommendation(@PathVariable Long id, Principal principal) {
        PolicyDocument policy = policies.findById(id);
        List<String> visas = policy.getVisaType() == null ? List.of()
                : Arrays.stream(policy.getVisaType().split(",")).map(String::trim).toList();
        var document = new FederalRegisterDocument(policy.getExternalId(), policy.getTitle(),
                policy.getStatus().name(), policy.getPublicationDate(), policy.getEffectiveDate(),
                policy.getSourceUrl(), null, List.of());
        var classification = new VisaClassification(visas.contains("F-1"), visas.contains("J-1"),
                visas.contains("H-1B"), visas, List.of());
        return store.getOrGenerate(principal.getName(), policy,
                () -> assessments.assess(principal.getName(), document, policy.getAgency(),
                        policy.getContent(), classification));
    }
}
