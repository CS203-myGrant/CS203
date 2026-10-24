package mygrant.email;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The outbox. Every status change is a conditional update that names the status it expects,
 * so two overlapping runs can never both act on one row: the loser updates nothing. Each
 * update runs and commits in its own short transaction when called outside one, which keeps
 * the provider call free of any transaction or row lock.
 */
public interface EmailDeliveryRepository extends JpaRepository<EmailDelivery, UUID> {

    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO email_deliveries (id, user_id, kind, status, attempts, next_attempt_at, created_at)
            VALUES (:id, :userId, 'IMMEDIATE', 'PENDING', 0, :now, :now)""", nativeQuery = true)
    int insertImmediate(@Param("id") UUID id, @Param("userId") Long userId, @Param("now") Instant now);

    /** Inserts the user's digest for that date, or nothing (returning 0) when one already exists. */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO email_deliveries (id, user_id, kind, digest_date, status, attempts, next_attempt_at, created_at)
            VALUES (:id, :userId, 'DIGEST', :digestDate, 'PENDING', 0, :now, :now)
            ON CONFLICT (user_id, digest_date) DO NOTHING""", nativeQuery = true)
    int insertDigest(@Param("id") UUID id, @Param("userId") Long userId,
            @Param("digestDate") LocalDate digestDate, @Param("now") Instant now);

    @Query("""
            select d.id from EmailDelivery d
            where d.status = mygrant.email.DeliveryStatus.PENDING and d.nextAttemptAt <= :now
            order by d.nextAttemptAt""")
    List<UUID> findDueIds(@Param("now") Instant now, Pageable limit);

    /** Takes a due row for sending and counts the attempt; 0 when another run took it first. */
    @Modifying
    @Transactional
    @Query("""
            update EmailDelivery d
            set d.status = mygrant.email.DeliveryStatus.SENDING, d.attempts = d.attempts + 1, d.claimedAt = :now
            where d.id = :id and d.status = mygrant.email.DeliveryStatus.PENDING and d.nextAttemptAt <= :now""")
    int claim(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("""
            update EmailDelivery d
            set d.status = mygrant.email.DeliveryStatus.SENT, d.sentAt = :now,
                d.providerMessageId = :providerMessageId, d.lastError = null
            where d.id = :id and d.status = mygrant.email.DeliveryStatus.SENDING""")
    int markSent(@Param("id") UUID id, @Param("providerMessageId") String providerMessageId,
            @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("""
            update EmailDelivery d
            set d.status = mygrant.email.DeliveryStatus.PENDING, d.nextAttemptAt = :retryAt, d.lastError = :error
            where d.id = :id and d.status = mygrant.email.DeliveryStatus.SENDING""")
    int markRetry(@Param("id") UUID id, @Param("retryAt") Instant retryAt, @Param("error") String error);

    /** Ends a claimed row as FAILED or SKIPPED. */
    @Modifying
    @Transactional
    @Query("""
            update EmailDelivery d set d.status = :status, d.lastError = :error
            where d.id = :id and d.status = mygrant.email.DeliveryStatus.SENDING""")
    int markFinished(@Param("id") UUID id, @Param("status") DeliveryStatus status, @Param("error") String error);

    /** Rows whose run died mid-send go back in the queue; their attempt is already counted. */
    @Modifying
    @Transactional
    @Query("""
            update EmailDelivery d
            set d.status = mygrant.email.DeliveryStatus.PENDING, d.nextAttemptAt = :now,
                d.lastError = 'Interrupted while sending'
            where d.status = mygrant.email.DeliveryStatus.SENDING and d.claimedAt < :cutoff
              and d.attempts < :maxAttempts""")
    int requeueStuck(@Param("cutoff") Instant cutoff, @Param("maxAttempts") int maxAttempts,
            @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("""
            update EmailDelivery d
            set d.status = mygrant.email.DeliveryStatus.FAILED, d.lastError = 'Interrupted while sending'
            where d.status = mygrant.email.DeliveryStatus.SENDING and d.claimedAt < :cutoff
              and d.attempts >= :maxAttempts""")
    int failStuck(@Param("cutoff") Instant cutoff, @Param("maxAttempts") int maxAttempts);

    void deleteAllByUserId(Long userId);
}
