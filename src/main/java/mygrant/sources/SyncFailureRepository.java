package mygrant.sources;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SyncFailureRepository extends JpaRepository<SyncFailure, Long> {

    /** The source's documents in this status, oldest failure first. */
    @Query("""
            select f.documentId from SyncFailure f
            where f.source.id = :sourceId and f.status = :status
            order by f.firstFailedAt, f.id
            """)
    List<String> findDocumentIds(@Param("sourceId") Long sourceId, @Param("status") SyncFailure.Status status);

    List<SyncFailure> findBySource_IdAndDocumentIdIn(Long sourceId, Collection<String> documentIds);

    /** Newest failure first. */
    Page<SyncFailure> findAllByOrderByLastFailedAtDescIdDesc(Pageable pageable);

    /** Newest failure first. */
    Page<SyncFailure> findByStatusOrderByLastFailedAtDescIdDesc(SyncFailure.Status status, Pageable pageable);
}
