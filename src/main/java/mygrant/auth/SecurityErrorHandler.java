package mygrant.auth;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Hands rejections from the security filters to GlobalExceptionHandler, so a 401 or 403 gets
 * the same ApiError body as every other error.
 */
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final HandlerExceptionResolver exceptionResolver;

    public SecurityErrorHandler(HandlerExceptionResolver exceptionResolver) {
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        reject(request, response, exception, HttpStatus.UNAUTHORIZED);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException exception) throws IOException {
        reject(request, response, exception, HttpStatus.FORBIDDEN);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, Exception exception,
            HttpStatus status) throws IOException {
        // Null means no handler wrote a response; a rejection must never fall through as a 200.
        if (exceptionResolver.resolveException(request, response, null, exception) == null) {
            response.sendError(status.value());
        }
    }
}
