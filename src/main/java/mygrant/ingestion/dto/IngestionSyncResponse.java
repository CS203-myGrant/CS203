package mygrant.ingestion.dto;

import java.time.Instant;

public record IngestionSyncResponse(
        Long sourceId,
        Instant startedAt,
        Instant finishedAt,
        int documentsFetched,
        int chunksVectorised,
        boolean succeeded,
        String error
) {
}
