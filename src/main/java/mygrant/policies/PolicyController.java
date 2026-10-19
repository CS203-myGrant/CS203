package mygrant.policies;

import java.util.List;

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
    public List<PolicyDocument> findAll() {
        return policyService.findAll();
    }

    @GetMapping("/{id}")
    public PolicyDocument findById(@PathVariable Long id) {
        return policyService.findById(id);
    }
}
