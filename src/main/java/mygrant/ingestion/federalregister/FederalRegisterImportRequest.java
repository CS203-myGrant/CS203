package mygrant.ingestion.federalregister;

/**
 * A document number and the signed-in admin whose profile is assessed. Bulk imports skip
 * the AI impact assessment, which is slow and billed per document.
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
