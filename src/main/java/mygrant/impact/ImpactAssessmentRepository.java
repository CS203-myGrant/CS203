package mygrant.impact;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ImpactAssessmentRepository extends JpaRepository<ImpactAssessment, Long> {
    /** JPQL gate for saved assessments aliased a; always combine with the authenticated owner. */
    String SAVED_VISIBLE = """
            a.status = mygrant.impact.AssessmentStatus.COMPLETED
            and (a.reviewStatus = mygrant.impact.AssessmentReviewStatus.APPROVED
                 or (a.reviewStatus = mygrant.impact.AssessmentReviewStatus.NOT_REQUIRED and a.requiresReview = false))
            """;

    Optional<ImpactAssessment> findByUserIdAndPolicyIdAndProfileVersion(
            Long userId, Long policyId, int profileVersion);
    List<ImpactAssessment> findByUserIdOrderByCreatedAtDesc(Long userId);
    /** Processing queries above are unrestricted; owner-facing reads use these gates. */
    @org.springframework.data.jpa.repository.Query("select a from ImpactAssessment a where a.userId = :userId and "
            + SAVED_VISIBLE + " order by a.createdAt desc, a.id desc")
    List<ImpactAssessment> findVisibleByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);

    @org.springframework.data.jpa.repository.Query("select a from ImpactAssessment a where a.userId = :userId and "
            + SAVED_VISIBLE)
    org.springframework.data.domain.Page<ImpactAssessment> findVisibleByUserId(
            @org.springframework.data.repository.query.Param("userId") Long userId,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query("select a from ImpactAssessment a where a.userId = :userId "
            + "and a.id = :id and " + SAVED_VISIBLE)
    Optional<ImpactAssessment> findVisibleByIdAndUserId(@org.springframework.data.repository.query.Param("id") Long id,
            @org.springframework.data.repository.query.Param("userId") Long userId);

    String READ_SELECT = "select new mygrant.impact.SavedImpactAssessmentResponse(a, p.title, p.sourceUrl) "
            + "from ImpactAssessment a join PolicyDocument p on p.id = a.policyId ";
    String READ_WHERE = "where a.userId = :userId and " + SAVED_VISIBLE
            + " and (:urgency is null or a.urgency = :urgency) "
            + "and (:policyId is null or a.policyId = :policyId) ";

    @org.springframework.data.jpa.repository.Query(value = READ_SELECT + READ_WHERE
            + "order by case a.urgency when mygrant.impact.ImpactUrgency.HIGH then 0 "
            + "when mygrant.impact.ImpactUrgency.MEDIUM then 1 "
            + "when mygrant.impact.ImpactUrgency.LOW then 2 else 3 end, a.createdAt desc, a.id desc",
            countQuery = "select count(a) from ImpactAssessment a " + READ_WHERE)
    org.springframework.data.domain.Page<SavedImpactAssessmentResponse> findVisibleResponses(
            @org.springframework.data.repository.query.Param("userId") Long userId,
            @org.springframework.data.repository.query.Param("urgency") ImpactUrgency urgency,
            @org.springframework.data.repository.query.Param("policyId") Long policyId,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query(READ_SELECT
            + "where a.userId = :userId and a.id = :id and " + SAVED_VISIBLE)
    Optional<SavedImpactAssessmentResponse> findVisibleResponse(
            @org.springframework.data.repository.query.Param("userId") Long userId,
            @org.springframework.data.repository.query.Param("id") Long id);

    List<ImpactAssessment> findByStatus(AssessmentStatus status);

    /** Records which per-user feed row this processing result was published as. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = "UPDATE impact_assessments SET recommendation_id = :recommendationId WHERE id = :id",
            nativeQuery = true)
    int linkRecommendation(@Param("id") long id, @Param("recommendationId") long recommendationId);
}
