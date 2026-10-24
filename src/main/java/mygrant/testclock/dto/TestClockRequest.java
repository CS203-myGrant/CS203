package mygrant.testclock.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** The date the app should treat as today. */
public record TestClockRequest(
        @Schema(description = "Simulated current date", example = "2026-09-15",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "A date is required")
        LocalDate date
) {
}
