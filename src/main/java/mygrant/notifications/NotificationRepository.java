package mygrant.notifications;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** JPA access to notifications, always scoped to the owning user. */
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    Page<Notification> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Optional<Notification> findByIdAndUserId(Long id, Long userId);

    long countByUserIdAndReadAtIsNull(Long userId);

    void deleteAllByUserId(Long userId);

    @Query("select n.policyId from Notification n where n.userId = :userId")
    Set<Long> findPolicyIdsByUserId(@Param("userId") Long userId);

    @Query("select n.userId from Notification n where n.policyId = :policyId")
    Set<Long> findUserIdsByPolicyId(@Param("policyId") Long policyId);

    /** Removes the user's alerts for policies published after the date; returns how many went. */
    @Modifying
    @Query(value = """
            DELETE FROM notifications n USING policy_documents p
            WHERE n.policy_id = p.id AND n.user_id = :userId AND p.publication_date > :date""",
            nativeQuery = true)
    int deleteForPoliciesPublishedAfter(@Param("userId") Long userId, @Param("date") LocalDate date);

    // CS203-91: alerts are no longer the unit an email carries; ImpactEmailAlertRepository is.
    // notifications.email_delivery_id keeps the history of emails sent before that change.
}
