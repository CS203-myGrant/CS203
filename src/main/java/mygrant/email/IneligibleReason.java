package mygrant.email;

/** Why an account cannot receive alert emails. */
public enum IneligibleReason {
    ACCOUNT_INACTIVE,
    NO_EMAIL_ADDRESS,
    /** Only WorkOS sign-in verifies an address; password-registered accounts are unverified. */
    EMAIL_NOT_VERIFIED
}
