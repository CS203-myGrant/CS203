package mygrant.policies;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** JPA access to stored policies, including idempotency lookup by external ID. */
public interface PolicyDocumentRepository extends JpaRepository<PolicyDocument, Long> {
    Optional<PolicyDocument> findByExternalId(String externalId);

    List<PolicyDocument> findBySourceTypeAndPublicationDateLessThanEqualOrderByPublicationDateAscIdAsc(
            SourceType sourceType, LocalDate publicationDate);
}
