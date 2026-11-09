package mygrant.email;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One alert email in the outbox. Rows are inserted and moved between statuses only by the
 * conditional queries in {@link EmailDeliveryRepository}; the entity has no setters, so a
 * loaded row is never written back.
 */
@Entity
@Table(name = "email_deliveries")
public class EmailDelivery {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DeliveryKind kind;

    @Column(name = "digest_date")
    private LocalDate digestDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DeliveryStatus status;

    /** Counted when a run claims the row, so a send that crashes the process still uses one up. */
    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "last_error", length = 300)
    private String lastError;

    @Column(name = "provider_message_id", length = 100)
    private String providerMessageId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected EmailDelivery() {
    }

    public UUID getId() { return id; }
    public Long getUserId() { return userId; }
    public DeliveryKind getKind() { return kind; }
    public LocalDate getDigestDate() { return digestDate; }
    public DeliveryStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getClaimedAt() { return claimedAt; }
    public String getLastError() { return lastError; }
    public String getProviderMessageId() { return providerMessageId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
}
