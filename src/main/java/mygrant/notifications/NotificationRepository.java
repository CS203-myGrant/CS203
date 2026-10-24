package mygrant.notifications;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** JPA access to notifications, always scoped to the owning user. */
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    Page<Notification> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Optional<Notification> findByIdAndUserId(Long id, Long userId);

    long countByUserIdAndReadAtIsNull(Long userId);

    void deleteAllByUserId(Long userId);

    /** Unread alerts created since the cutoff that no email has claimed yet, oldest first. */
    @Query("""
            select n from Notification n
            where n.emailDeliveryId is null and n.readAt is null and n.createdAt >= :since
            order by n.createdAt, n.id""")
    List<Notification> findNotEmailedSince(@Param("since") Instant since);

    @Query("""
            select n from Notification n
            where n.userId = :userId and n.emailDeliveryId is null and n.readAt is null
              and n.createdAt >= :since
            order by n.createdAt, n.id""")
    List<Notification> findNotEmailedSinceForUser(@Param("userId") Long userId, @Param("since") Instant since);

    List<Notification> findByEmailDeliveryIdOrderByCreatedAtAscIdAsc(UUID emailDeliveryId);

    /** Puts the alerts in that email unless another email already holds them; returns how many it got. */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE notifications SET email_delivery_id = :deliveryId
            WHERE id IN (:ids) AND email_delivery_id IS NULL""", nativeQuery = true)
    int claimForDelivery(@Param("deliveryId") UUID deliveryId, @Param("ids") Collection<Long> ids);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE notifications SET email_delivery_id = NULL
            WHERE email_delivery_id = :deliveryId AND id IN (:ids)""", nativeQuery = true)
    int releaseFromDelivery(@Param("deliveryId") UUID deliveryId, @Param("ids") Collection<Long> ids);
}
