package mygrant.common;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import mygrant.ingestion.PolicyAlreadyExistsException;
import mygrant.feedback.FeedbackAccessDeniedException;
import mygrant.feedback.FeedbackNotFoundException;
import mygrant.notifications.NotificationNotFoundException;
import mygrant.policies.PolicyNotFoundException;


@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> handleInvalidCredentials(InvalidCredentialsException exception) {
        return response(HttpStatus.UNAUTHORIZED, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ApiError> handleDuplicateEmail(DuplicateEmailException exception) {
        return response(HttpStatus.BAD_REQUEST, "Registration could not be completed", Map.of());
    }

    @ExceptionHandler(PolicyNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(PolicyNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(NotificationNotFoundException.class)
    public ResponseEntity<ApiError> handleNotificationNotFound(NotificationNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(FeedbackNotFoundException.class)
    public ResponseEntity<ApiError> handleFeedbackNotFound(FeedbackNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(FeedbackAccessDeniedException.class)
    public ResponseEntity<ApiError> handleFeedbackAccessDenied(FeedbackAccessDeniedException exception) {
        return response(HttpStatus.FORBIDDEN, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(PolicyAlreadyExistsException.class)
    public ResponseEntity<ApiError> handleConflict(PolicyAlreadyExistsException exception) {
        return response(HttpStatus.CONFLICT, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(DocumentValidationException.class)
    public ResponseEntity<ApiError> handleDocumentValidation(
            DocumentValidationException exception) {
        return response(
                HttpStatus.BAD_REQUEST,
                exception.getMessage(),
                exception.getValidationErrors()
        );
    }

    @ExceptionHandler(ProfileValidationException.class)
    public ResponseEntity<ApiError> handleProfileValidation(
            ProfileValidationException exception) {
        return response(
                HttpStatus.BAD_REQUEST,
                exception.getMessage(),
                exception.getValidationErrors()
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException exception) {
        return response(HttpStatus.BAD_REQUEST, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return response(HttpStatus.BAD_REQUEST, "Request validation failed", errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableRequest(HttpMessageNotReadableException exception) {
        return response(HttpStatus.BAD_REQUEST, "Request body is invalid. Check the JSON and date values.", Map.of());
    }

    private ResponseEntity<ApiError> response(
            HttpStatus status, String message, Map<String, String> validationErrors) {
        ApiError error = new ApiError(Instant.now(), status.value(), status.getReasonPhrase(),
                message, validationErrors);
        return ResponseEntity.status(status).body(error);
    }
}
