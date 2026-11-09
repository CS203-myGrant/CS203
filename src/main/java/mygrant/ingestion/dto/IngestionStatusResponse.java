package mygrant.ingestion.dto;

import java.time.Instant;

import mygrant.sources.PolicySourceType;

public record IngestionStatusResponse(
        Long sourceId,
        String sourceName,
        PolicySourceType sourceType,
        String url,
        boolean enabled,
        Instant lastRunAt,
        int documentsFetched,
        int chunksVectorised,
        long failureCount,
        String lastError
) {
}
