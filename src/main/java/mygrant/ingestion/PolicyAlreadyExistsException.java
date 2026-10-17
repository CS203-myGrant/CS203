package mygrant.ingestion;

/** Raised when an existing external ID has different or unindexed content. */

public class PolicyAlreadyExistsException extends RuntimeException {
    public PolicyAlreadyExistsException(String externalId) {
        super("Policy document " + externalId
                + " already exists with different content; versioned updates are not implemented yet");
    }
}
