package mygrant.sources;

import java.net.URI;
import java.security.Principal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mygrant.sources.dto.PolicySourceRequest;
import mygrant.sources.dto.PolicySourceResponse;
import mygrant.user.User;
import mygrant.user.UserRepository;

@Service
public class PolicySourceService {

    private final PolicySourceRepository sourceRepository;
    private final PolicySourceChangeLogRepository changeLogRepository;
    private final UserRepository userRepository;

    public PolicySourceService(PolicySourceRepository sourceRepository,
            PolicySourceChangeLogRepository changeLogRepository, UserRepository userRepository) {
        this.sourceRepository = sourceRepository;
        this.changeLogRepository = changeLogRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<PolicySourceResponse> findAll() {
        return sourceRepository.findAllByOrderByIdAsc().stream()
                .map(PolicySourceResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public PolicySourceResponse findById(Long sourceId) {
        return PolicySourceResponse.from(source(sourceId));
    }

    @Transactional
    public PolicySourceResponse create(PolicySourceRequest request, Principal principal) {
        validateUrl(request.url());
        User actor = actor(principal);
        PolicySource source = new PolicySource(normalize(request.name()), request.type(),
                request.url().trim(), request.pollIntervalSeconds());
        source.setEnabled(request.isEnabledOrDefault());
        PolicySource saved = sourceRepository.save(source);
        audit(saved, actor, saved.isEnabled() ? "CREATE" : "DISABLE", "Source created");
        return PolicySourceResponse.from(saved);
    }

    @Transactional
    public PolicySourceResponse update(Long sourceId, PolicySourceRequest request,
            Principal principal) {
        validateUrl(request.url());
        User actor = actor(principal);
        PolicySource source = source(sourceId);
        boolean wasEnabled = source.isEnabled();
        source.update(normalize(request.name()), request.type(), request.url().trim(),
                request.pollIntervalSeconds(), request.isEnabledOrDefault());
        PolicySource saved = sourceRepository.save(source);
        String action = wasEnabled != saved.isEnabled()
                ? (saved.isEnabled() ? "ENABLE" : "DISABLE") : "UPDATE";
        audit(saved, actor, action, "Source settings updated");
        return PolicySourceResponse.from(saved);
    }

    @Transactional
    public PolicySourceResponse setEnabled(Long sourceId, boolean enabled, Principal principal) {
        User actor = actor(principal);
        PolicySource source = source(sourceId);
        if (source.isEnabled() != enabled) {
            source.setEnabled(enabled);
            sourceRepository.save(source);
            audit(source, actor, enabled ? "ENABLE" : "DISABLE",
                    enabled ? "Source enabled" : "Source disabled");
        }
        return PolicySourceResponse.from(source);
    }

    private PolicySource source(Long sourceId) {
        return sourceRepository.findById(sourceId)
                .orElseThrow(() -> new PolicySourceNotFoundException(sourceId));
    }

    private User actor(Principal principal) {
        return userRepository.findByEmail(principal.getName().trim().toLowerCase())
                .orElseThrow(() -> new IllegalStateException("Authenticated administrator no longer exists"));
    }

    private void audit(PolicySource source, User actor, String action, String details) {
        changeLogRepository.save(new PolicySourceChangeLog(source, actor, action, details));
    }

    private void validateUrl(String value) {
        try {
            URI uri = URI.create(value.trim());
            if (!"http".equalsIgnoreCase(uri.getScheme())
                    && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("URL must use http or https");
            }
            if (uri.getHost() == null) {
                throw new IllegalArgumentException("URL must include a host");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("URL must be a valid http or https URL", exception);
        }
    }

    private String normalize(String value) {
        return value.trim();
    }
}
