package mygrant.common;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The body of every error response")
public record ApiError(
        @Schema(description = "When the error happened", example = "2026-10-25T14:03:12.481Z")
        Instant timestamp,
        @Schema(description = "HTTP status code", example = "400")
        int status,
        @Schema(description = "HTTP reason phrase", example = "Bad Request")
        String error,
        @Schema(description = "What went wrong, safe to show to the user", example = "Request validation failed")
        String message,
        @Schema(description = "Field name to problem for request validation errors; empty otherwise",
                example = "{\"question\": \"must not be blank\"}")
        Map<String, String> validationErrors
) {

    public static ApiError of(HttpStatusCode status, String message, Map<String, String> validationErrors) {
        HttpStatus known = HttpStatus.resolve(status.value());
        return new ApiError(Instant.now(), status.value(), known == null ? "" : known.getReasonPhrase(),
                message, validationErrors);
    }

    public static ApiError of(HttpStatusCode status, String message) {
        return of(status, message, Map.of());
    }
}
