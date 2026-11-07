package mygrant.ingestion.dto;

import java.time.Instant;

import mygrant.sources.SyncFailure;

public record SyncFailureResponse(
        Long id,
        Long sourceId,
        String sourceName,
        String documentId,
        SyncFailure.Status status,
        int attempts,
        int maxAttempts,
        String lastError,
        Instant firstFailedAt,
        Instant lastFailedAt,
        Instant resolvedAt
) {
}
