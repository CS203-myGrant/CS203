package mygrant.policies;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only HTTP access to stored policy records. */
@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {

    private final PolicyService policyService;

    public PolicyController(PolicyService policyService) {
        this.policyService = policyService;
    }

    @GetMapping
    public Page<PolicyDocument> findAll(@PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return policyService.findAll(pageable);
    }

    @GetMapping("/{id}")
    public PolicyDocument findById(@PathVariable Long id) {
        return policyService.findById(id);
    }
}
