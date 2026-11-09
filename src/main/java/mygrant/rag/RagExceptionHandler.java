package mygrant.rag;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

import mygrant.common.ApiError;

/** Runs before GlobalExceptionHandler, whose catch-all would otherwise turn an AI outage into a 500. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = RagController.class)
public class RagExceptionHandler {
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ApiError> unavailable(RestClientException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                .body(ApiError.of(HttpStatus.SERVICE_UNAVAILABLE,
                        "The AI service is unavailable. Please try again later or choose Talk to a person."));
    }
}
