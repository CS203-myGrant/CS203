package mygrant.user;

import java.util.List;
import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileFieldConsentRepository
        extends JpaRepository<ProfileFieldConsent, ProfileFieldConsentId> {

    List<ProfileFieldConsent> findByUserId(Long userId);

    List<ProfileFieldConsent> findByUserIdIn(Collection<Long> userIds);

    void deleteByUserIdAndFieldName(
            Long userId,
            ProfileFieldDefinition fieldName);
}
