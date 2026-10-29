package mygrant.user;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@IdClass(ProfileFieldConsentId.class)
@Table(name = "profile_field_consents")
public class ProfileFieldConsent {
    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "field_name")
    private ProfileFieldDefinition fieldName;

    @Column(name = "consented_at", nullable = false)
    private Instant consentedAt;

    protected ProfileFieldConsent() {}

    public ProfileFieldConsent(Long userId, ProfileFieldDefinition fieldName, Instant consentedAt) {
        this.userId = userId;
        this.fieldName = fieldName;
        this.consentedAt = consentedAt;
    }

    public ProfileFieldDefinition getFieldName() { return fieldName; }
    public Instant getConsentedAt() { return consentedAt; }
    public Long getUserId() { return userId; }
}
