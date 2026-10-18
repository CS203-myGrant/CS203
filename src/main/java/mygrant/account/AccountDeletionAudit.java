package mygrant.account;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** PII-free evidence that an account purge completed. */
@Entity
@Table(name = "account_deletion_audit")
public class AccountDeletionAudit {

    private static final int CURRENT_PURGE_VERSION = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "deleted_at", nullable = false)
    private Instant deletedAt;

    @Column(name = "purge_version", nullable = false)
    private int purgeVersion;

    protected AccountDeletionAudit() {
    }

    public AccountDeletionAudit(Long userId, Instant deletedAt) {
        this.userId = userId;
        this.deletedAt = deletedAt;
        this.purgeVersion = CURRENT_PURGE_VERSION;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public int getPurgeVersion() {
        return purgeVersion;
    }
}
