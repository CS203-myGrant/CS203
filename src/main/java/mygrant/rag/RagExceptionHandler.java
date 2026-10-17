package mygrant.rag;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

@RestControllerAdvice(assignableTypes = RagController.class)
public class RagExceptionHandler {
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, String>> unavailable(RestClientException exception) {
        return ResponseEntity.status(503).body(Map.of("message",
                "The AI service is unavailable. Please try again later or choose Talk to a person."));
    }
}
