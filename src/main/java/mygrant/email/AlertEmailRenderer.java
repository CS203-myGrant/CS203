package mygrant.email;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import mygrant.notifications.Notification;
import mygrant.user.User;

/**
 * Builds alert emails: plain, escaped HTML plus a text part, with the alert's own message
 * and a link to it in the app. No impact analysis goes in an email. The output depends only
 * on the delivery and its stored alerts, never on the time of sending, because a retry has
 * to send exactly the same request under the same idempotency key.
 */
@Component
public class AlertEmailRenderer {

    private static final int SUBJECT_LENGTH = 90;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final EmailProperties properties;
    private final UnsubscribeTokens unsubscribeTokens;
    private final DateTimeFormatter timestamp;

    public AlertEmailRenderer(EmailProperties properties, UnsubscribeTokens unsubscribeTokens) {
        this.properties = properties;
        this.unsubscribeTokens = unsubscribeTokens;
        this.timestamp = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a z", Locale.ENGLISH)
                .withZone(properties.digestZone());
    }

    public EmailMessage render(EmailDelivery delivery, User user, List<Notification> notifications) {
        return switch (delivery.getKind()) {
            case IMMEDIATE -> immediate(delivery.getId(), user, notifications.get(0));
            case DIGEST -> digest(delivery.getId(), user, delivery.getDigestDate(), notifications);
        };
    }

    EmailMessage digest(UUID deliveryId, User user, LocalDate digestDate, List<Notification> notifications) {
        String unsubscribeUrl = properties.unsubscribeUrl(unsubscribeTokens.issue(user.getId()));
        String day = DAY.format(digestDate);
        String count = notifications.size() == 1 ? "1 new alert" : notifications.size() + " new alerts";
        Set<AlertBucket> buckets = notifications.stream().map(AlertBucket::of)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(AlertBucket.class)));
        String reason = (buckets.size() == 2 ? "critical and other alerts are"
                : buckets.contains(AlertBucket.CRITICAL) ? "critical alerts are" : "other alerts are")
                + " set to the daily digest";

        StringBuilder html = new StringBuilder("""
                <p style="margin:0 0 4px;font-size:13px;color:#6b7280;">myGRANT daily digest &middot; %s</p>
                <p style="margin:0 0 20px;font-size:16px;">%s for you.</p>
                """.formatted(escape(day), escape(count)));
        StringBuilder text = new StringBuilder("myGRANT daily digest - %s\n\n%s for you.\n"
                .formatted(day, count));
        for (Notification notification : notifications) {
            String label = notification.isActionRequired() ? "Action needed" : "For information";
            String when = timestamp.format(notification.getCreatedAt());
            String link = properties.notificationUrl(notification.getId());
            html.append("""
                    <div style="margin:0 0 20px;padding:0 0 0 12px;border-left:3px solid %s;">
                    <p style="margin:0 0 4px;font-size:13px;color:#6b7280;">%s &middot; %s</p>
                    <p style="margin:0 0 6px;font-size:15px;">%s</p>
                    <p style="margin:0;font-size:14px;"><a href="%s" style="color:#1f2937;">View in myGRANT</a></p>
                    </div>
                    """.formatted(AlertBucket.of(notification) == AlertBucket.CRITICAL ? "#b91c1c" : "#9ca3af",
                    escape(label), escape(when), escape(notification.getMessage()), escape(link)));
            text.append("\n%s - %s\n%s\nView in myGRANT: %s\n".formatted(label, when, notification.getMessage(), link));
        }
        text.append(textFooter(reason, unsubscribeUrl));

        return new EmailMessage(user.getEmail(), "Your myGRANT digest for " + day + ": " + count,
                page(html.toString(), reason, unsubscribeUrl), text.toString(), headers(unsubscribeUrl),
                deliveryId.toString());
    }

    EmailMessage immediate(UUID deliveryId, User user, Notification notification) {
        AlertBucket bucket = AlertBucket.of(notification);
        String unsubscribeUrl = properties.unsubscribeUrl(unsubscribeTokens.issue(user.getId()));
        String link = properties.notificationUrl(notification.getId());
        String when = timestamp.format(notification.getCreatedAt());
        String label = notification.isActionRequired() ? "Action needed" : "Policy alert";
        String reason = (bucket == AlertBucket.CRITICAL ? "critical alerts" : "other alerts")
                + " are set to arrive right away";

        String html = page("""
                <p style="margin:0 0 4px;font-size:13px;color:#6b7280;">myGRANT %s &middot; %s</p>
                <p style="margin:0 0 20px;font-size:16px;">%s</p>
                %s""".formatted(escape(label), escape(when), escape(notification.getMessage()), button(link)),
                reason, unsubscribeUrl);
        String text = """
                myGRANT %s - %s

                %s

                View in myGRANT: %s
                %s""".formatted(label, when, notification.getMessage(), link, textFooter(reason, unsubscribeUrl));

        return new EmailMessage(user.getEmail(), subject(label, notification.getMessage()), html, text,
                headers(unsubscribeUrl), deliveryId.toString());
    }

    private String page(String content, String reason, String unsubscribeUrl) {
        return """
                <!doctype html>
                <html lang="en">
                <body style="margin:0;padding:24px;background:#ffffff;color:#1f2937;font-family:Arial,Helvetica,sans-serif;line-height:1.5;">
                <div style="max-width:560px;">
                %s
                <hr style="border:0;border-top:1px solid #e5e7eb;margin:28px 0 12px;">
                <p style="margin:0;font-size:12px;color:#6b7280;">You get this email because your %s.
                <a href="%s" style="color:#6b7280;">Manage email settings</a> &middot;
                <a href="%s" style="color:#6b7280;">Unsubscribe</a></p>
                </div>
                </body>
                </html>
                """.formatted(content, escape(reason), escape(properties.settingsUrl()), escape(unsubscribeUrl));
    }

    private String button(String link) {
        return "<p style=\"margin:0;\"><a href=\"" + escape(link) + "\" style=\"display:inline-block;padding:10px 18px;"
                + "background:#1f2937;color:#ffffff;text-decoration:none;border-radius:6px;\">View in myGRANT</a></p>";
    }

    private String textFooter(String reason, String unsubscribeUrl) {
        return """

                --
                You get this email because your %s.
                Manage email settings: %s
                Unsubscribe: %s
                """.formatted(reason, properties.settingsUrl(), unsubscribeUrl);
    }

    /** RFC 8058 one-click unsubscribe; mail clients POST to the same URL as the body link. */
    private Map<String, String> headers(String unsubscribeUrl) {
        return Map.of(
                "List-Unsubscribe", "<" + unsubscribeUrl + ">",
                "List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
    }

    private String subject(String label, String message) {
        String oneLine = message.replaceAll("\\s+", " ").trim();
        if (oneLine.length() > SUBJECT_LENGTH) {
            int cut = oneLine.lastIndexOf(' ', SUBJECT_LENGTH);
            oneLine = oneLine.substring(0, cut > SUBJECT_LENGTH / 2 ? cut : SUBJECT_LENGTH) + "…";
        }
        return label + ": " + oneLine;
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
