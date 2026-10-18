package mygrant.account;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface AccountDeletionRequestRepository
        extends JpaRepository<AccountDeletionRequest, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AccountDeletionRequest> findByChallengeHashAndUserId(
            String challengeHash, Long userId);

    void deleteAllByUserIdAndConfirmedAtIsNull(Long userId);

    void deleteAllByUserId(Long userId);
}
