package mygrant.user;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImmigrationDocumentRepository
        extends JpaRepository<ImmigrationDocument, Long> {

    Page<ImmigrationDocument> findByUserIdOrderByUploadedAtDesc(Long userId, Pageable pageable);

    Optional<ImmigrationDocument> findByIdAndUserId(Long id, Long userId);

    void deleteAllByUserId(Long userId);
}
