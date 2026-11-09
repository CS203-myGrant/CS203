package mygrant.actions;

public class ActionItemNotFoundException extends RuntimeException {
    public ActionItemNotFoundException() {
        super("Action item not found");
    }
}
