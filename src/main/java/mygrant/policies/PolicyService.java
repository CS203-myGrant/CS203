package mygrant.policies;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class PolicyService {

    private final PolicyDocumentRepository repository;

    public PolicyService(PolicyDocumentRepository repository) {
        this.repository = repository;
    }

    public Page<PolicyDocument> findAll(Pageable pageable) {
        return repository.findAll(pageable);
    }

    public PolicyDocument findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new PolicyNotFoundException(id));
    }
}
