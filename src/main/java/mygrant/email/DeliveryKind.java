package mygrant.email;

public enum DeliveryKind {
    /** One alert, sent as soon as the dispatcher runs. */
    IMMEDIATE,
    /** Every new digest-bucket alert for one user and one local date. */
    DIGEST
}
