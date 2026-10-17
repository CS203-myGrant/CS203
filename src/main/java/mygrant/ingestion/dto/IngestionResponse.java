package mygrant.ingestion.dto;

import java.time.Instant;

/** Identifies an indexed policy and reports whether work was skipped as a duplicate. */
public record IngestionResponse(
        Long policyId,
        String externalId,
        int chunksIndexed,
        Instant indexedAt,
        boolean alreadyIndexed
) {
}
