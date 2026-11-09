package mygrant.actions;

/** How important a checklist item is, from the severity of the assessment that produced it. */
public enum ActionPriority {
    HIGH,
    MEDIUM,
    LOW;

    static ActionPriority fromSeverity(String severity) {
        return switch (severity) {
            case "CRITICAL", "HIGH" -> HIGH;
            case "MEDIUM" -> MEDIUM;
            case "LOW", "INFO" -> LOW;
            default -> throw new IllegalStateException("Unknown impact severity " + severity);
        };
    }
}
