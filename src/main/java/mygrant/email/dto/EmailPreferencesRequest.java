package mygrant.email.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Both modes are required; the values are checked by the service so a bad one gets a field error. */
public record EmailPreferencesRequest(
        @Schema(description = "How alerts that ask you to act are emailed",
                allowableValues = {"IMMEDIATE", "DIGEST", "OFF"}, example = "IMMEDIATE",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Must be one of IMMEDIATE, DIGEST or OFF")
        String criticalDelivery,

        @Schema(description = "How all other alerts are emailed",
                allowableValues = {"IMMEDIATE", "DIGEST", "OFF"}, example = "DIGEST",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Must be one of IMMEDIATE, DIGEST or OFF")
        String otherDelivery
) {
}
