package mygrant.common;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import mygrant.email.EmailNotConfiguredException;
import mygrant.ingestion.PolicyAlreadyExistsException;
import mygrant.feedback.FeedbackAccessDeniedException;
import mygrant.feedback.FeedbackNotFoundException;
import mygrant.notifications.NotificationNotFoundException;
import mygrant.policies.PolicyNotFoundException;
import mygrant.sources.PolicySourceNotFoundException;
import mygrant.user.UserNotFoundException;


/**
 * Gives every error an ApiError body. Spring MVC's own exceptions (unknown route, wrong method or
 * content type, missing parameter, ResponseStatusException) arrive through the base class, and
 * SecurityErrorHandler forwards 401 and 403 from the security filters. Bodies are always JSON,
 * even when the client accepts only text/event-stream, and never include stack traces or the
 * messages of unexpected exceptions.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String UNEXPECTED = "Something went wrong. Please try again later.";

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

    @ExceptionHandler(PolicySourceNotFoundException.class)
    public ResponseEntity<ApiError> handlePolicySourceNotFound(PolicySourceNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiError> handleUserNotFound(UserNotFoundException exception) {
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

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return response(HttpStatus.BAD_REQUEST, "Invalid value for " + exception.getName(), Map.of());
    }

    @ExceptionHandler(EmailNotConfiguredException.class)
    public ResponseEntity<ApiError> handleEmailNotConfigured(EmailNotConfiguredException exception) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException exception) {
        return response(HttpStatus.UNAUTHORIZED, "Please sign in to continue", Map.of());
    }

    @ExceptionHandler(ProfileIncompleteException.class)
    public ResponseEntity<ApiError> handleProfileIncomplete(ProfileIncompleteException exception) {
        return response(HttpStatus.FORBIDDEN, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException exception) {
        return response(HttpStatus.FORBIDDEN, "You do not have permission to do this", Map.of());
    }

    /** A list endpoint's sort parameter named a property the records do not have. */
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ApiError> handleUnknownSortProperty(PropertyReferenceException exception) {
        return response(HttpStatus.BAD_REQUEST, "Cannot sort by " + exception.getPropertyName(), Map.of());
    }

    /** Anything not handled above. The details go to the log, never to the client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request,
            HttpServletResponse servletResponse) {
        LOGGER.error("Unhandled error for {} {}", request.getMethod(), request.getRequestURI(), exception);
        if (servletResponse.isCommitted()) {
            return null;
        }
        return response(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED, Map.of());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return errorResponse(HttpStatus.BAD_REQUEST, headers)
                .body(ApiError.of(HttpStatus.BAD_REQUEST, "Request validation failed", errors));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return errorResponse(HttpStatus.BAD_REQUEST, headers).body(ApiError.of(HttpStatus.BAD_REQUEST,
                "Request body is invalid. Check the JSON and date values."));
    }

    /** Every other Spring MVC exception, ResponseStatusException included, ends up here. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ResponseEntity<Object> problem = super.handleExceptionInternal(exception, body, headers, status, request);
        if (problem == null) {
            // Already committed, e.g. a notification stream whose client went away.
            return null;
        }
        return errorResponse(status, problem.getHeaders())
                .body(ApiError.of(status, message(exception, problem.getBody(), status, request)));
    }

    private static String message(Exception exception, Object body, HttpStatusCode status, WebRequest request) {
        if ((exception instanceof NoResourceFoundException || exception instanceof NoHandlerFoundException)
                && request instanceof ServletWebRequest servletRequest) {
            return "No endpoint " + servletRequest.getHttpMethod() + " " + servletRequest.getRequest().getRequestURI();
        }
        String detail = body instanceof ProblemDetail problem ? problem.getDetail() : null;
        // A server error keeps its detail only when the code chose it, as a ResponseStatusException reason.
        if (status.is5xxServerError() && !(exception instanceof ResponseStatusException)) {
            return UNEXPECTED;
        }
        if (detail != null) {
            return detail;
        }
        HttpStatus known = HttpStatus.resolve(status.value());
        return known == null ? UNEXPECTED : known.getReasonPhrase();
    }

    private ResponseEntity<ApiError> response(
            HttpStatus status, String message, Map<String, String> validationErrors) {
        return errorResponse(status, HttpHeaders.EMPTY).body(ApiError.of(status, message, validationErrors));
    }

    /** JSON whatever the client accepts, so a stream or HTML request still gets a readable error. */
    private static ResponseEntity.BodyBuilder errorResponse(HttpStatusCode status, HttpHeaders headers) {
        return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_JSON);
    }
}
