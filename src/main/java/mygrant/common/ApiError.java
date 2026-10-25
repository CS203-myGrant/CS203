package mygrant.common;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
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
