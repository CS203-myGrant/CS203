package mygrant.user;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByEmail(String email);
    Optional<User> findByEmail(String email);
    Optional<User> findByWorkosUserId(String workosUserId);
    List<User> findAllByActiveTrueOrderByIdAsc();
    Page<User> findAllByActiveTrueOrderByIdAsc(Pageable pageable);

    /**
     * Matches users by role and visa type. Registration stores the visa type verbatim, so
     * both sides are upper-cased and trimmed; callers pass values normalized the same way.
     */
    @Query("select u from User u where u.role = :role and upper(trim(u.visaType)) in :visaTypes")
    List<User> findByRoleAndVisaTypeIn(@Param("role") UserRole role, @Param("visaTypes") List<String> visaTypes);
}
