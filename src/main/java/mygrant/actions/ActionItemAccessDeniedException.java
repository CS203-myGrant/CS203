package mygrant.actions;

/** Unlike the impact feed's 404, a visible item owned by someone else is a 403, as CS203-4 requires. */
public class ActionItemAccessDeniedException extends RuntimeException {
    public ActionItemAccessDeniedException() {
        super("This action item belongs to another user");
    }
}
