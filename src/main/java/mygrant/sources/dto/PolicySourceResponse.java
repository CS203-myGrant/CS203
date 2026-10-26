package mygrant.sources.dto;

import java.time.Instant;

import mygrant.sources.PolicySource;
import mygrant.sources.PolicySourceType;

public record PolicySourceResponse(
        Long id,
        String name,
        PolicySourceType type,
        String url,
        boolean enabled,
        int pollIntervalSeconds,
        Instant lastSuccessAt,
        Instant lastFailureAt,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
    public static PolicySourceResponse from(PolicySource source) {
        return new PolicySourceResponse(source.getId(), source.getName(), source.getType(),
                source.getUrl(), source.isEnabled(), source.getPollIntervalSeconds(),
                source.getLastSuccessAt(), source.getLastFailureAt(), source.getLastError(),
                source.getCreatedAt(), source.getUpdatedAt());
    }
}
