package mygrant.ingestion.federalregister;

/**
 * A document number and importing admin. The legacy assessImpact flag is retained for
 * caller compatibility; all new imports now assess matching profiles asynchronously.
 */
public record FederalRegisterImportRequest(String documentNumber, String userEmail, boolean assessImpact) {
    public FederalRegisterImportRequest {
        if (documentNumber == null || documentNumber.isBlank()) {
            throw new IllegalArgumentException("Federal Register document number is required");
        }
        if (userEmail == null || userEmail.isBlank()) {
            throw new IllegalArgumentException("Signed-in user email is required");
        }
    }

    public FederalRegisterImportRequest(String documentNumber, String userEmail) {
        this(documentNumber, userEmail, true);
    }
}
