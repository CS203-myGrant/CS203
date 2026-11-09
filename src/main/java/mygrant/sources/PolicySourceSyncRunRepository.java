package mygrant.sources;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PolicySourceSyncRunRepository extends JpaRepository<PolicySourceSyncRun, Long> {
    Optional<PolicySourceSyncRun> findTopBySource_IdOrderByStartedAtDesc(Long sourceId);

    @Query("select count(r) from PolicySourceSyncRun r where r.source.id = :sourceId and r.succeeded = false")
    long countFailures(@Param("sourceId") Long sourceId);
}
