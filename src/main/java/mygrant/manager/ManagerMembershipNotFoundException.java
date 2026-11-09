package mygrant.manager;

public class ManagerMembershipNotFoundException extends RuntimeException {
    public ManagerMembershipNotFoundException(Long userId) {
        super("User is not linked to this manager: " + userId);
    }
}
