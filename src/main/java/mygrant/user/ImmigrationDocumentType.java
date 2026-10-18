package mygrant.user;

import java.util.Arrays;

public enum ImmigrationDocumentType {
    PASSPORT,
    VISA_STAMP,
    I20,
    DS2019,
    I94,
    EAD,
    I797,
    I140,
    PERM,
    OTHER;

    public static ImmigrationDocumentType from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Document type is required");
        }

        return Arrays.stream(values())
                .filter(type -> type.name().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported document type"));
    }
}