package mygrant.email;

/** Sends transactional email through a provider. */
public interface EmailSender {

    /** False when the provider has no credentials; callers then send nothing at all. */
    boolean isConfigured();

    /**
     * Sends one message and returns the provider's id for it.
     *
     * @throws EmailSendException when the provider rejects the message or cannot be reached
     */
    String send(EmailMessage message);
}
