package mygrant.sources;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicySourceRepository extends JpaRepository<PolicySource, Long> {
    List<PolicySource> findAllByOrderByIdAsc();

    List<PolicySource> findAllByEnabledTrueOrderByIdAsc();
}
