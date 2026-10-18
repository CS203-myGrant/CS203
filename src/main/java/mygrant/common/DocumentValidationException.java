package mygrant.common;

import java.util.Map;

public class DocumentValidationException extends RuntimeException {

    private final Map<String, String> validationErrors;

    public DocumentValidationException(Map<String, String> validationErrors) {
        super("Request validation failed");
        this.validationErrors = validationErrors;
    }

    public Map<String, String> getValidationErrors() {
        return validationErrors;
    }
}