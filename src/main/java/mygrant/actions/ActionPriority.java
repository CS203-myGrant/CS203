package mygrant.actions;

/** How important a checklist item is, from the severity of the assessment that produced it. */
public enum ActionPriority {
    HIGH,
    MEDIUM,
    LOW;

    static ActionPriority fromSeverity(String severity) {
        return switch (severity) {
            case "HIGH" -> HIGH;
            case "MEDIUM" -> MEDIUM;
            case "LOW", "NOT_APPLICABLE" -> LOW;
            default -> throw new IllegalStateException("Unknown impact severity " + severity);
        };
    }
}
