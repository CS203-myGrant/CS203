package mygrant.ingestion.connector;

import java.time.Instant;

/** A source response with its URL, retrieval time, and SHA-256 content hash. */
public record ConnectorFetch<T>(T payload, String sourceUrl, Instant fetchedAt, String contentHash) {
    public ConnectorFetch {
        if (payload == null || sourceUrl == null || sourceUrl.isBlank() || fetchedAt == null
                || contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Fetched content, source URL, timestamp and SHA-256 hash are required");
        }
    }
}
