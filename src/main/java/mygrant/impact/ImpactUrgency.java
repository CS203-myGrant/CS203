package mygrant.impact;

/** Canonical profile-specific impact scale. Availability and review are separate states. */
public enum ImpactUrgency {
    /** Direct status, work, travel, filing or deadline risk needing prompt action. */
    HIGH,
    /** Likely relevant, without immediate urgency, or important facts remain uncertain. */
    MEDIUM,
    /** Relevant information or a reminder with no near-term action. */
    LOW,
    /** No material impact on the supplied profile. */
    NOT_APPLICABLE;

    public static java.util.List<String> labels() {
        return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
}
