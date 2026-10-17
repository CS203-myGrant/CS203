package mygrant.feedback;

public class FeedbackAccessDeniedException extends RuntimeException {
    public FeedbackAccessDeniedException() { super("Staff access is required"); }
}
