package mygrant.impact;

/** The report cannot be filed as sent: one is already open, or the assessment changed meanwhile. */
public class RecommendationReportConflictException extends RuntimeException {
    public RecommendationReportConflictException(String message) {
        super(message);
    }
}
