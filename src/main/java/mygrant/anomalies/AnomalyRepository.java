package mygrant.anomalies;
import java.util.Optional;
import org.springframework.data.domain.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface AnomalyRepository extends JpaRepository<Anomaly,Long>{ Page<Anomaly> findByTypeAndStatusOrderByCreatedAtDesc(AnomalyType t,AnomalyStatus s,Pageable p); Page<Anomaly> findByTypeOrderByCreatedAtDesc(AnomalyType t,Pageable p); Page<Anomaly> findByStatusOrderByCreatedAtDesc(AnomalyStatus s,Pageable p); Page<Anomaly> findAllByOrderByCreatedAtDesc(Pageable p); Optional<Anomaly> findFirstByTypeAndSourceIdAndStatusAndDetailsStartingWithOrderByCreatedAtDesc(AnomalyType t,Long s,AnomalyStatus status,String prefix); }
