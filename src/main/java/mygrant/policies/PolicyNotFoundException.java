package mygrant.policies;

public class PolicyNotFoundException extends RuntimeException {
    public PolicyNotFoundException(Long id) {
        super("Policy document " + id + " was not found");
    }
}
