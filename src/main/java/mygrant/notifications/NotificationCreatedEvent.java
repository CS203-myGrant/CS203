package mygrant.notifications;

import mygrant.notifications.dto.NotificationResponse;

/** Published for each new notification so it can be pushed to the recipient's open pages. */
public record NotificationCreatedEvent(String recipientEmail, NotificationResponse notification) {
}
