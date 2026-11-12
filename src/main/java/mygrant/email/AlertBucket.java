package mygrant.email;

import java.util.Optional;

import mygrant.impact.ImpactUrgency;

/**
 * Which of the user's two email settings applies to an item (CS203-91). The AI assessment's
 * severity decides, not the keyword flag on the in-app alert, which keeps its own rules:
 * HIGH uses the critical setting; MEDIUM, LOW and changes we could not assess yet use the
 * other setting; NOT_APPLICABLE is never emailed.
 */
public enum AlertBucket {
    CRITICAL,
    OTHER;

    /** Empty for NOT_APPLICABLE; null urgency means the change could not be assessed yet. */
    public static Optional<AlertBucket> of(ImpactUrgency urgency) {
        if (urgency == ImpactUrgency.NOT_APPLICABLE) {
            return Optional.empty();
        }
        return Optional.of(urgency == ImpactUrgency.HIGH ? CRITICAL : OTHER);
    }

    public static AlertBucket of(ImpactEmailAlert alert) {
        return of(alert.getUrgency()).orElseThrow(() -> new IllegalStateException("NOT_APPLICABLE is never emailed"));
    }

    public DeliveryMode modeIn(EmailPreference preference) {
        return this == CRITICAL ? preference.getCriticalDelivery() : preference.getOtherDelivery();
    }

    /**
     * How the user gets this item: their setting for its bucket, except that a change we could not
     * assess yet is never emailed on its own straight away, only in the digest.
     */
    public static DeliveryMode modeFor(ImpactEmailAlert alert, EmailPreference preference) {
        DeliveryMode mode = of(alert).modeIn(preference);
        return alert.isUnassessed() && mode == DeliveryMode.IMMEDIATE ? DeliveryMode.DIGEST : mode;
    }
}
