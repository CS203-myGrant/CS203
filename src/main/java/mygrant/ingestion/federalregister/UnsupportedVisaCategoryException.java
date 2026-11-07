package mygrant.ingestion.federalregister;

/**
 * The classifier found no supported visa category (F-1, J-1 or H-1B) in a Federal Register
 * document, so it was not ingested. The scheduled sync's search only finds candidates, so there
 * this is an expected outcome, not a failure to retry. It is still an IllegalArgumentException,
 * so a manual import of such a document is a 400 as before.
 */
public class UnsupportedVisaCategoryException extends IllegalArgumentException {

    public UnsupportedVisaCategoryException(String message) {
        super(message);
    }
}
