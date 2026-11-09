package mygrant.sources.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import mygrant.sources.PolicySourceType;

public record PolicySourceRequest(
        @NotBlank String name,
        @NotNull PolicySourceType type,
        @NotBlank String url,
        @Positive int pollIntervalSeconds,
        Boolean enabled
) {
    public boolean isEnabledOrDefault() {
        return enabled == null || enabled;
    }
}
