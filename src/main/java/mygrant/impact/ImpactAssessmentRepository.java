package mygrant.impact;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ImpactAssessmentRepository extends JpaRepository<ImpactAssessment, Long> {
    Optional<ImpactAssessment> findByUserIdAndPolicyIdAndProfileVersion(
            Long userId, Long policyId, int profileVersion);
    List<ImpactAssessment> findByUserIdOrderByCreatedAtDesc(Long userId);
    /** User-facing consumers must use this gate, rather than the unrestricted processing queries. */
    @org.springframework.data.jpa.repository.Query("""
            select a from ImpactAssessment a where a.userId = :userId
            and a.status = mygrant.impact.AssessmentStatus.COMPLETED
            and (a.reviewStatus = mygrant.impact.AssessmentReviewStatus.NOT_REQUIRED
                 or a.reviewStatus = mygrant.impact.AssessmentReviewStatus.APPROVED)
            order by a.createdAt desc, a.id desc
            """)
    List<ImpactAssessment> findVisibleByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);

    List<ImpactAssessment> findByStatus(AssessmentStatus status);

    /** Records which per-user feed row this processing result was published as. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = "UPDATE impact_assessments SET recommendation_id = :recommendationId WHERE id = :id",
            nativeQuery = true)
    int linkRecommendation(@Param("id") long id, @Param("recommendationId") long recommendationId);
}
