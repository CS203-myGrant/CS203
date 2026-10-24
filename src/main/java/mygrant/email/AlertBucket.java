package mygrant.email;

import mygrant.impact.ImpactUrgency;
import mygrant.notifications.Notification;

/** Which of the user's two email settings applies to an alert. */
public enum AlertBucket {
    CRITICAL,
    OTHER;

    /**
     * The one rule for critical alerts: the reader must act, or impact assessment rated the
     * alert HIGH. Nothing fills impact_severity per recipient yet, so today this is just
     * actionRequired.
     */
    public static AlertBucket of(Notification notification) {
        return notification.isActionRequired()
                || ImpactUrgency.HIGH.name().equalsIgnoreCase(notification.getImpactSeverity())
                ? CRITICAL : OTHER;
    }

    public DeliveryMode modeIn(EmailPreference preference) {
        return this == CRITICAL ? preference.getCriticalDelivery() : preference.getOtherDelivery();
    }
}
