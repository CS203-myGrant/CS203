package mygrant.common;

import java.util.Map;

public class ProfileValidationException extends RuntimeException {

    private final Map<String, String> validationErrors;

    public ProfileValidationException(Map<String, String> validationErrors) {
        super("Request validation failed");
        this.validationErrors = validationErrors;
    }

    public Map<String, String> getValidationErrors() {
        return validationErrors;
    }
}