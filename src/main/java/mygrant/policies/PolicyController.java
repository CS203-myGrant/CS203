package mygrant.policies;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springdoc.core.annotations.ParameterObject;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Read-only HTTP access to stored policy records. */
@RestController
@RequestMapping("/api/v1/policies")
@Tag(name = "Policies")
public class PolicyController {

    private final PolicyService policyService;

    public PolicyController(PolicyService policyService) {
        this.policyService = policyService;
    }

    @GetMapping
    @Operation(summary = "List stored policies",
            description = "Each record includes the full source text. Sorted by id unless `sort` is given.")
    @ApiResponse(responseCode = "200", description = "A page of policies")
    @ApiResponse(responseCode = "400", description = "Unknown sort property")
    public Page<PolicyDocument> findAll(
            @ParameterObject @PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return policyService.findAll(pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one stored policy")
    @ApiResponse(responseCode = "200", description = "The policy")
    @ApiResponse(responseCode = "400", description = "The id is not a number")
    @ApiResponse(responseCode = "404", description = "No policy has this id")
    public PolicyDocument findById(@PathVariable Long id) {
        return policyService.findById(id);
    }
}
