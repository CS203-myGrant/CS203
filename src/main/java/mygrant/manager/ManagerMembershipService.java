package mygrant.manager;

import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import mygrant.manager.dto.ManagerMemberResponse;
import mygrant.user.User;
import mygrant.user.UserNotFoundException;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

@Service
public class ManagerMembershipService {
    private final ManagerMembershipRepository memberships;
    private final UserRepository users;

    public ManagerMembershipService(ManagerMembershipRepository memberships, UserRepository users) {
        this.memberships = memberships;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<ManagerMemberResponse> list(String managerEmail) {
        User manager = manager(managerEmail);
        List<Long> ids = memberships.findAllByManagerIdOrderByCreatedAtAscUserIdAsc(manager.getId())
                .stream().map(ManagerMembership::getUserId).toList();
        return users.findAllById(ids).stream().map(ManagerMemberResponse::from).toList();
    }

    @Transactional
    public ManagerMemberResponse add(String managerEmail, Long userId) {
        User manager = manager(managerEmail);
        User user = applicant(userId);
        if (memberships.findByManagerIdAndUserId(manager.getId(), userId).isPresent()) {
            throw new ManagerMembershipConflictException(userId);
        }
        try {
            memberships.saveAndFlush(new ManagerMembership(manager.getId(), userId));
        } catch (DataIntegrityViolationException exception) {
            throw new ManagerMembershipConflictException(userId);
        }
        return ManagerMemberResponse.from(user);
    }

    @Transactional
    public void remove(String managerEmail, Long userId) {
        User manager = manager(managerEmail);
        ManagerMembership membership = memberships.findByManagerIdAndUserId(manager.getId(), userId)
                .orElseThrow(() -> new ManagerMembershipNotFoundException(userId));
        memberships.delete(membership);
    }

    private User manager(String email) {
        User user = users.findByEmail(email).orElseThrow(
                () -> new IllegalStateException("Authenticated user no longer exists"));
        if (user.getRole() != UserRole.MANAGER) {
            throw new IllegalStateException("Authenticated user is not a manager");
        }
        return user;
    }

    private User applicant(Long userId) {
        User user = users.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        if (user.getRole() != UserRole.APPLICANT || !user.isActive()) {
            throw new UserNotFoundException(userId);
        }
        return user;
    }
}
