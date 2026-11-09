package mygrant.actions;

/** Where the owner is with one checklist item. Any status may move to any other. */
public enum ActionStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    DISMISSED;

    /** Active items are the ones still to do: they appear in the default list and can be urgent. */
    public boolean isActive() {
        return this == PENDING || this == IN_PROGRESS;
    }
}
