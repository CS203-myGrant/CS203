package mygrant.account;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountDeletionAuditRepository
        extends JpaRepository<AccountDeletionAudit, Long> {

    boolean existsByUserId(Long userId);
}
