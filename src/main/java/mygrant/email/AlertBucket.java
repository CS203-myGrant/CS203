package mygrant.email;

import mygrant.notifications.Notification;

/** Which of the user's two email settings applies to an alert. */
public enum AlertBucket {
    CRITICAL,
    OTHER;

    /** Alerts that require action use the user's critical email setting. */
    public static AlertBucket of(Notification notification) {
        return notification.isActionRequired() ? CRITICAL : OTHER;
    }

    public DeliveryMode modeIn(EmailPreference preference) {
        return this == CRITICAL ? preference.getCriticalDelivery() : preference.getOtherDelivery();
    }
}
