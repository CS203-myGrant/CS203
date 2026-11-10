package mygrant.impact;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Persisted result for one policy and one version of a user's profile. */
@Entity
@Table(name = "impact_assessments", uniqueConstraints = @UniqueConstraint(
        name = "impact_assessments_user_policy_profile_key",
        columnNames = {"user_id", "policy_id", "profile_version"}))
public class ImpactAssessment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "policy_id", nullable = false)
    private Long policyId;

    @Column(name = "profile_version", nullable = false)
    private int profileVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AssessmentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "urgency", length = 20)
    private ImpactUrgency urgency;

    @Column(name = "confidence")
    private Integer confidence;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reasons", nullable = false, columnDefinition = "jsonb")
    private List<String> reasons;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recommended_actions", nullable = false, columnDefinition = "jsonb")
    private List<String> recommendedActions;

    @Column(name = "requires_review", nullable = false)
    private boolean requiresReview;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 20)
    private AssessmentReviewStatus reviewStatus;

    @Column(name = "provider", length = 100)
    private String provider;

    @Column(name = "model", length = 255)
    private String model;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ImpactAssessment() {}

    public ImpactAssessment(Long userId, Long policyId, int profileVersion,
            AssessmentStatus status, ImpactUrgency urgency, Integer confidence,
            String summary, List<String> reasons, List<String> recommendedActions,
            boolean requiresReview, AssessmentReviewStatus reviewStatus, String provider, String model) {
        if (profileVersion < 1) throw new IllegalArgumentException("Profile version must be positive");
        if (confidence != null && (confidence < 0 || confidence > 100)) {
            throw new IllegalArgumentException("Confidence must be between 0 and 100");
        }
        if (urgency == ImpactUrgency.UNAVAILABLE) {
            throw new IllegalArgumentException("UNAVAILABLE is a processing status, not an urgency");
        }
        if (status == AssessmentStatus.COMPLETED && (urgency == null || confidence == null || summary == null)) {
            throw new IllegalArgumentException("Completed assessments require urgency, confidence and summary");
        }
        if (status == AssessmentStatus.UNAVAILABLE && (urgency != null || confidence != null)) {
            throw new IllegalArgumentException("Unavailable assessments have no urgency or confidence");
        }
        if (requiresReview == (reviewStatus == AssessmentReviewStatus.NOT_REQUIRED)) {
            throw new IllegalArgumentException("Review status must agree with requiresReview");
        }
        this.userId = Objects.requireNonNull(userId);
        this.policyId = Objects.requireNonNull(policyId);
        this.profileVersion = profileVersion;
        this.status = Objects.requireNonNull(status);
        this.urgency = urgency;
        this.confidence = confidence;
        this.summary = summary;
        this.reasons = List.copyOf(reasons);
        this.recommendedActions = List.copyOf(recommendedActions);
        this.requiresReview = requiresReview;
        this.reviewStatus = Objects.requireNonNull(reviewStatus);
        this.provider = provider;
        this.model = model;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getPolicyId() { return policyId; }
    public int getProfileVersion() { return profileVersion; }
    public AssessmentStatus getStatus() { return status; }
    public ImpactUrgency getUrgency() { return urgency; }
    public Integer getConfidence() { return confidence; }
    public String getSummary() { return summary; }
    public List<String> getReasons() { return List.copyOf(reasons); }
    public List<String> getRecommendedActions() { return List.copyOf(recommendedActions); }
    public boolean isRequiresReview() { return requiresReview; }
    public AssessmentReviewStatus getReviewStatus() { return reviewStatus; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public Instant getCreatedAt() { return createdAt; }
}
