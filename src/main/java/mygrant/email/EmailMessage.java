package mygrant.email;

import java.util.Map;

/**
 * One email ready to send. The idempotency key lets the provider drop a repeat of the same
 * request, so a retry after a timeout cannot deliver the email twice.
 */
public record EmailMessage(
        String to,
        String subject,
        String html,
        String text,
        Map<String, String> headers,
        String idempotencyKey
) {
    public EmailMessage {
        headers = Map.copyOf(headers);
    }
}
