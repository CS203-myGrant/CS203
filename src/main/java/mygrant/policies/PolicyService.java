package mygrant.policies;

import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class PolicyService {

    private final PolicyDocumentRepository repository;

    public PolicyService(PolicyDocumentRepository repository) {
        this.repository = repository;
    }

    public List<PolicyDocument> findAll() {
        return repository.findAll();
    }

    public PolicyDocument findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new PolicyNotFoundException(id));
    }
}
