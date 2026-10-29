package mygrant.user;

import java.io.Serializable;
import java.util.Objects;

public final class ProfileFieldConsentId implements Serializable {
    private Long userId;
    private ProfileFieldDefinition fieldName;

    public ProfileFieldConsentId() {
    }

    public ProfileFieldConsentId(Long userId, ProfileFieldDefinition fieldName) {
        this.userId = userId;
        this.fieldName = fieldName;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ProfileFieldConsentId that)) return false;
        return Objects.equals(userId, that.userId)
                && fieldName == that.fieldName;
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, fieldName);
    }
}