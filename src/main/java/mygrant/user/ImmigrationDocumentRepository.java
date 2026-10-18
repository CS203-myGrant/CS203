package mygrant.user;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ImmigrationDocumentRepository
        extends JpaRepository<ImmigrationDocument, Long> {

    List<ImmigrationDocument> findByUserIdOrderByUploadedAtDesc(Long userId);

    Optional<ImmigrationDocument> findByIdAndUserId(Long id, Long userId);

    void deleteAllByUserId(Long userId);
}
