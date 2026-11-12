package mygrant.email;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** The items emails carry. Claims are conditional updates, so an item is in at most one email. */
public interface ImpactEmailAlertRepository extends JpaRepository<ImpactEmailAlert, Long> {

    /** Items planned or changed since the cutoff that no email has claimed yet, oldest first. */
    @Query("""
            select a from ImpactEmailAlert a
            where a.emailDeliveryId is null and a.updatedAt >= :since
            order by a.updatedAt, a.id""")
    List<ImpactEmailAlert> findNotEmailedSince(@Param("since") Instant since);

    @Query("""
            select a from ImpactEmailAlert a
            where a.userId = :userId and a.emailDeliveryId is null and a.updatedAt >= :since
            order by a.updatedAt, a.id""")
    List<ImpactEmailAlert> findNotEmailedSinceForUser(@Param("userId") Long userId, @Param("since") Instant since);

    List<ImpactEmailAlert> findByEmailDeliveryIdOrderByCreatedAtAscIdAsc(UUID emailDeliveryId);

    /** Puts the items in that email unless another email already holds them; returns how many it got. */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE impact_email_alerts SET email_delivery_id = :deliveryId
            WHERE id IN (:ids) AND email_delivery_id IS NULL""", nativeQuery = true)
    int claimForDelivery(@Param("deliveryId") UUID deliveryId, @Param("ids") Collection<Long> ids);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE impact_email_alerts SET email_delivery_id = NULL
            WHERE email_delivery_id = :deliveryId AND id IN (:ids)""", nativeQuery = true)
    int releaseFromDelivery(@Param("deliveryId") UUID deliveryId, @Param("ids") Collection<Long> ids);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM impact_email_alerts WHERE user_id = :userId", nativeQuery = true)
    int deleteAllByUserId(@Param("userId") Long userId);
}
