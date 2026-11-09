package mygrant.ingestion.connector;

import mygrant.policies.SourceType;

/** Stable source key and its primary/secondary classification. */
public record SourceIdentity(String id, SourceType type) {
    public SourceIdentity {
        if (id == null || id.isBlank() || type == null) {
            throw new IllegalArgumentException("Connector source id and type are required");
        }
    }
}
