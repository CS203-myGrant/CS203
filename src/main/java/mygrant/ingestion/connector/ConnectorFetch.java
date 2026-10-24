package mygrant.ingestion.connector;

import java.time.Instant;

/** A source response with its URL and the time it was retrieved. */
public record ConnectorFetch<T>(T payload, String sourceUrl, Instant fetchedAt) {
    public ConnectorFetch {
        if (payload == null || sourceUrl == null || sourceUrl.isBlank() || fetchedAt == null) {
            throw new IllegalArgumentException("Fetched content, source URL and timestamp are required");
        }
    }
}
