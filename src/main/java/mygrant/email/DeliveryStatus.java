package mygrant.email;

public enum DeliveryStatus {
    /** Waiting for its next attempt. */
    PENDING,
    /** Claimed by a dispatcher run that is talking to the provider. */
    SENDING,
    SENT,
    /** Gave up: a permanent provider error, or out of attempts. */
    FAILED,
    /** Not sent because the user unsubscribed or can no longer receive email. */
    SKIPPED
}
