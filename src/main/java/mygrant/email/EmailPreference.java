package mygrant.email;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A user's alert email choices. Kept apart from user_profiles so changing them does not
 * bump the profile version that notifications record.
 */
@Entity
@Table(name = "email_preferences")
public class EmailPreference {

    public static final DeliveryMode DEFAULT_CRITICAL = DeliveryMode.IMMEDIATE;
    public static final DeliveryMode DEFAULT_OTHER = DeliveryMode.DIGEST;

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "critical_delivery", nullable = false, length = 10)
    private DeliveryMode criticalDelivery;

    @Enumerated(EnumType.STRING)
    @Column(name = "other_delivery", nullable = false, length = 10)
    private DeliveryMode otherDelivery;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EmailPreference() {
    }

    public EmailPreference(Long userId, DeliveryMode criticalDelivery, DeliveryMode otherDelivery) {
        this.userId = userId;
        this.criticalDelivery = criticalDelivery;
        this.otherDelivery = otherDelivery;
        this.updatedAt = Instant.now();
    }

    /** What a user who never saved a choice gets; not persisted until they change it. */
    public static EmailPreference defaults(Long userId) {
        return new EmailPreference(userId, DEFAULT_CRITICAL, DEFAULT_OTHER);
    }

    public Long getUserId() { return userId; }
    public DeliveryMode getCriticalDelivery() { return criticalDelivery; }
    public DeliveryMode getOtherDelivery() { return otherDelivery; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void update(DeliveryMode criticalDelivery, DeliveryMode otherDelivery) {
        this.criticalDelivery = criticalDelivery;
        this.otherDelivery = otherDelivery;
        this.updatedAt = Instant.now();
    }
}
