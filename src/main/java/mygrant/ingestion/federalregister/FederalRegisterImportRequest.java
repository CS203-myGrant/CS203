package mygrant.ingestion.federalregister;

/** A document number and the signed-in admin whose profile is assessed. */
public record FederalRegisterImportRequest(String documentNumber, String userEmail) {
    public FederalRegisterImportRequest {
        if (documentNumber == null || documentNumber.isBlank()) {
            throw new IllegalArgumentException("Federal Register document number is required");
        }
        if (userEmail == null || userEmail.isBlank()) {
            throw new IllegalArgumentException("Signed-in user email is required");
        }
    }
}
