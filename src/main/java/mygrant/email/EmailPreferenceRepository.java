package mygrant.email;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailPreferenceRepository extends JpaRepository<EmailPreference, Long> {
}
