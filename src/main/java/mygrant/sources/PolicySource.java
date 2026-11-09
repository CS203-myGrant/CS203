package mygrant.sources;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "policy_sources")
public class PolicySource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PolicySourceType type;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "poll_interval_seconds", nullable = false)
    private int pollIntervalSeconds;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PolicySource() {
    }

    public PolicySource(String name, PolicySourceType type, String url, int pollIntervalSeconds) {
        this.name = name;
        this.type = type;
        this.url = url;
        this.pollIntervalSeconds = pollIntervalSeconds;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public PolicySourceType getType() { return type; }
    public String getUrl() { return url; }
    public boolean isEnabled() { return enabled; }
    public int getPollIntervalSeconds() { return pollIntervalSeconds; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public Instant getLastFailureAt() { return lastFailureAt; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void update(String name, PolicySourceType type, String url, int pollIntervalSeconds,
            boolean enabled) {
        this.name = name;
        this.type = type;
        this.url = url;
        this.pollIntervalSeconds = pollIntervalSeconds;
        this.enabled = enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void recordSuccess(Instant completedAt) {
        this.lastSuccessAt = completedAt;
        this.lastError = null;
    }

    public void recordFailure(Instant failedAt, String error) {
        this.lastFailureAt = failedAt;
        this.lastError = error;
    }
}
