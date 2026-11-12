package mygrant.impact;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import mygrant.sources.FreshnessPage;
import mygrant.sources.SourceFreshnessService;
import mygrant.user.UserRepository;

/** Owner-scoped reads of ingestion assessments; hidden and foreign IDs both produce 404. */
@Service
@Transactional(readOnly = true)
public class SavedImpactAssessmentService {
    private final ImpactAssessmentRepository assessments;
    private final UserRepository users;
    private final SourceFreshnessService freshness;

    public SavedImpactAssessmentService(ImpactAssessmentRepository assessments, UserRepository users,
            SourceFreshnessService freshness) {
        this.assessments = assessments;
        this.users = users;
        this.freshness = freshness;
    }

    public FreshnessPage<SavedImpactAssessmentResponse> list(String email, Pageable pageable, ImpactUrgency urgency, Long policyId) {
        if (pageable.getPageSize() < 1 || pageable.getPageSize() > 100) {
            throw new IllegalArgumentException("Page size must be between 1 and 100");
        }
        // The contract fixes ordering; caller-supplied Spring sort parameters cannot override it.
        Page<SavedImpactAssessmentResponse> page = assessments.findVisibleResponses(userId(email), urgency, policyId,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
        // CS203-175: the sources are read once for the whole page.
        var sources = freshness.snapshot();
        return new FreshnessPage<>(page.getContent().stream()
                .map(response -> response.withSourceFreshness(sources.forUrls(urls(response))))
                .toList(), page.getPageable(), page.getTotalElements(), sources.all());
    }

    public SavedImpactAssessmentResponse get(String email, Long id) {
        SavedImpactAssessmentResponse response = assessments.findVisibleResponse(userId(email), id)
                .orElseThrow(ImpactNotFoundException::new);
        return response.withSourceFreshness(freshness.snapshot().forUrls(urls(response)));
    }

    private static List<String> urls(SavedImpactAssessmentResponse response) {
        return response.sourceUrl() == null ? List.of() : List.of(response.sourceUrl());
    }

    private Long userId(String email) {
        return users.findByEmail(email).orElseThrow(() -> new IllegalStateException("User no longer exists")).getId();
    }
}
