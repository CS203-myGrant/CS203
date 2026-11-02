package mygrant.manager;

public class ManagerMembershipConflictException extends RuntimeException {
    public ManagerMembershipConflictException(Long userId) {
        super("User is already linked to this manager: " + userId);
    }
}
