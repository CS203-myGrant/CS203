package mygrant.manager;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManagerMembershipRepository extends JpaRepository<ManagerMembership, Long> {
    List<ManagerMembership> findAllByManagerIdOrderByCreatedAtAscUserIdAsc(Long managerId);
    Optional<ManagerMembership> findByManagerIdAndUserId(Long managerId, Long userId);
}
