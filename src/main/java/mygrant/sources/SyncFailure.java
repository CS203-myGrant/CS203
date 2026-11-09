package mygrant.sources;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A document that failed during a source sync. The sync tries it again on later runs until it
 * succeeds or has failed maxAttempts times in a row, after which it is skipped for good.
 */
@Entity
@Table(name = "sync_failures")
public class SyncFailure {

    public enum Status {
        /** Tried again on each later run. */
        RETRYING,
        /** Failed maxAttempts times in a row; later runs skip it. */
        PERMANENTLY_FAILED,
        /** A later run imported it. Kept so admins can see what went wrong. */
        RESOLVED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private PolicySource source;

    @Column(name = "document_id", nullable = false, length = 255)
    private String documentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", nullable = false, columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "first_failed_at", nullable = false)
    private Instant firstFailedAt;

    @Column(name = "last_failed_at", nullable = false)
    private Instant lastFailedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected SyncFailure() {
    }

    /** The document's first failure. */
    public SyncFailure(PolicySource source, String documentId, String error, Instant failedAt, int maxAttempts) {
        this.source = source;
        this.documentId = documentId;
        this.attempts = 0;
        this.firstFailedAt = failedAt;
        failed(error, failedAt, maxAttempts);
    }

    /**
     * Counts another failed attempt, and gives up once there have been maxAttempts. A document that
     * fails again after it was resolved starts a new count.
     */
    public void failedAgain(String error, Instant failedAt, int maxAttempts) {
        if (status == Status.RESOLVED) {
            attempts = 0;
            firstFailedAt = failedAt;
            resolvedAt = null;
        }
        failed(error, failedAt, maxAttempts);
    }

    /** A later run imported the document. */
    public void resolve(Instant resolvedAt) {
        this.status = Status.RESOLVED;
        this.resolvedAt = resolvedAt;
    }

    public boolean isPermanentlyFailed() {
        return status == Status.PERMANENTLY_FAILED;
    }

    private void failed(String error, Instant failedAt, int maxAttempts) {
        attempts++;
        lastError = error;
        lastFailedAt = failedAt;
        status = attempts >= maxAttempts ? Status.PERMANENTLY_FAILED : Status.RETRYING;
    }

    public Long getId() { return id; }
    public PolicySource getSource() { return source; }
    public String getDocumentId() { return documentId; }
    public Status getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getFirstFailedAt() { return firstFailedAt; }
    public Instant getLastFailedAt() { return lastFailedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
}
