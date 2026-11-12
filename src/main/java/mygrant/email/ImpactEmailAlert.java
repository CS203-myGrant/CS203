package mygrant.email;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import mygrant.impact.ImpactUrgency;

/**
 * One email-worthy item about one user's policy (CS203-91), planned by {@link ImpactEmailPlanner}
 * and carried by an email in the outbox. Rows are written only by conditional SQL in the planner
 * and {@link ImpactEmailAlertRepository}; the entity has no setters, so a loaded row is never
 * written back.
 */
@Entity
@Table(name = "impact_email_alerts")
public class ImpactEmailAlert {

    /** FIRST is the one email about a change; ESCALATION follows when it later becomes HIGH. */
    public enum Kind { FIRST, ESCALATION }

    @Id
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "policy_id", nullable = false)
    private Long policyId;

    /** The in-app alert about the same change, linked to when there is no assessment to open. */
    @Column(name = "notification_id")
    private Long notificationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Kind kind;

    /** The published assessment the email describes; null when we could not assess it yet. */
    @Column(name = "assessment_id")
    private Long assessmentId;

    @Column(name = "assessment_version")
    private Integer assessmentVersion;

    /** HIGH, MEDIUM or LOW; null means we could not assess this change yet. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ImpactUrgency urgency;

    @Column(name = "policy_title", nullable = false, columnDefinition = "text")
    private String policyTitle;

    /** The assessment's plain-English summary, or the alert's own message when unassessed. */
    @Column(nullable = false, columnDefinition = "text")
    private String summary;

    @Column(name = "email_delivery_id", insertable = false, updatable = false)
    private UUID emailDeliveryId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ImpactEmailAlert() {
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getPolicyId() { return policyId; }
    public Long getNotificationId() { return notificationId; }
    public Kind getKind() { return kind; }
    public Long getAssessmentId() { return assessmentId; }
    public Integer getAssessmentVersion() { return assessmentVersion; }
    public ImpactUrgency getUrgency() { return urgency; }
    public String getPolicyTitle() { return policyTitle; }
    public String getSummary() { return summary; }
    public UUID getEmailDeliveryId() { return emailDeliveryId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** True for a change we could not assess yet: it only ever goes in the digest. */
    public boolean isUnassessed() {
        return urgency == null;
    }
}
