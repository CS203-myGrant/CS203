package mygrant.sources;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import mygrant.user.User;

@Entity
@Table(name = "policy_source_change_log")
public class PolicySourceChangeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private PolicySource source;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_user_id", nullable = false)
    private User actor;

    @Column(name = "actor_email", nullable = false, length = 100)
    private String actorEmail;

    @Column(nullable = false, length = 20)
    private String action;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    protected PolicySourceChangeLog() {
    }

    public PolicySourceChangeLog(PolicySource source, User actor, String action, String details) {
        this.source = source;
        this.actor = actor;
        this.actorEmail = actor.getEmail();
        this.action = action;
        this.details = details;
        this.changedAt = Instant.now();
    }

    public Long getId() { return id; }
    public PolicySource getSource() { return source; }
    public User getActor() { return actor; }
    public String getActorEmail() { return actorEmail; }
    public String getAction() { return action; }
    public String getDetails() { return details; }
    public Instant getChangedAt() { return changedAt; }
}
