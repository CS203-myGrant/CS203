package mygrant.sources;

public class PolicySourceNotFoundException extends RuntimeException {

    public PolicySourceNotFoundException(Long sourceId) {
        super("Policy source not found: " + sourceId);
    }
}
