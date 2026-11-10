package mygrant.impact;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImpactAssessmentRepository extends JpaRepository<ImpactAssessment, Long> {
    Optional<ImpactAssessment> findByUserIdAndPolicyIdAndProfileVersion(
            Long userId, Long policyId, int profileVersion);
    List<ImpactAssessment> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<ImpactAssessment> findByStatus(AssessmentStatus status);
}
