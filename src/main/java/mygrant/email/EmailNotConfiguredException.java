package mygrant.email;

public class EmailNotConfiguredException extends RuntimeException {

    public EmailNotConfiguredException() {
        super("Email delivery is not configured. Set RESEND_API_KEY and RESEND_FROM_EMAIL.");
    }
}
