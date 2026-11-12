package mygrant.impact;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import mygrant.user.UserRepository;

/** Owner-scoped reads of ingestion assessments; hidden and foreign IDs both produce 404. */
@Service
@Transactional(readOnly = true)
public class SavedImpactAssessmentService {
    private final ImpactAssessmentRepository assessments;
    private final UserRepository users;

    public SavedImpactAssessmentService(ImpactAssessmentRepository assessments, UserRepository users) {
        this.assessments = assessments;
        this.users = users;
    }

    public Page<SavedImpactAssessmentResponse> list(String email, Pageable pageable, ImpactUrgency urgency, Long policyId) {
        if (pageable.getPageSize() < 1 || pageable.getPageSize() > 100) {
            throw new IllegalArgumentException("Page size must be between 1 and 100");
        }
        // The contract fixes ordering; caller-supplied Spring sort parameters cannot override it.
        return assessments.findVisibleResponses(userId(email), urgency, policyId,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
    }

    public SavedImpactAssessmentResponse get(String email, Long id) {
        return assessments.findVisibleResponse(userId(email), id).orElseThrow(ImpactNotFoundException::new);
    }

    private Long userId(String email) {
        return users.findByEmail(email).orElseThrow(() -> new IllegalStateException("User no longer exists")).getId();
    }
}
