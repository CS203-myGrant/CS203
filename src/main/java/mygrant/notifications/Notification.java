package mygrant.notifications;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** An in-app alert telling one user that a policy may affect their application. */
@Entity
@Table(name = "notifications")
public class Notification {

    private static final Set<String> KNOWN_FIELDS = Arrays.stream(ProfileField.values())
            .map(ProfileField::name).collect(Collectors.toUnmodifiableSet());

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "profile_version", nullable = false)
    private int profileVersion; 

    @Column(name = "policy_id", nullable = false)
    private Long policyId;

    @Column(nullable = false, length = 1500)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "action_required", nullable = false)
    private boolean actionRequired;

    /** Comma-separated {@link ProfileField} names the user should review; null when none. */
    @Column(name = "affected_fields")
    private String affectedFields;

    protected Notification() {
    }

    public Notification(Long userId, int profileVersion, Long policyId,
        String message, List<ProfileField> affectedFields) {
        this.userId = userId;
        this.policyId = policyId;
        this.message = message;
        this.actionRequired = !affectedFields.isEmpty();
        this.affectedFields = affectedFields.isEmpty() ? null
                : affectedFields.stream().map(ProfileField::name).collect(Collectors.joining(","));
        this.profileVersion = profileVersion;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getPolicyId() { return policyId; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReadAt() { return readAt; }
    public boolean isActionRequired() { return actionRequired; }
    public int getProfileVersion() { return profileVersion; }   

    public List<ProfileField> getAffectedFields() {
        if (affectedFields == null || affectedFields.isBlank()) {
            return List.of();
        }
        // Skip names a later release renamed or removed, so one stale row degrades on its
        // own instead of failing the whole inbox.
        return Arrays.stream(affectedFields.split(","))
                .filter(KNOWN_FIELDS::contains)
                .map(ProfileField::valueOf)
                .toList();
    }

    public void markRead(Instant readAt) {
        if (this.readAt == null) {
            this.readAt = readAt;
        }
    }
}
