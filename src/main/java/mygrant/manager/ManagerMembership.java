package mygrant.manager;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A manager's explicit link to an applicant they oversee. */
@Entity
@Table(name = "manager_user_memberships")
public class ManagerMembership {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "manager_id", nullable = false)
    private Long managerId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ManagerMembership() {}

    public ManagerMembership(Long managerId, Long userId) {
        this.managerId = managerId;
        this.userId = userId;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getManagerId() { return managerId; }
    public Long getUserId() { return userId; }
    public Instant getCreatedAt() { return createdAt; }
}
