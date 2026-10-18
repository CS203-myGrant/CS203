package mygrant.notifications.dto;

import java.time.Instant;
import java.util.List;

import mygrant.notifications.Notification;
import mygrant.notifications.ProfileField;

/** A notification as shown in the user's inbox. */
public record NotificationResponse(
        Long id,
        Long policyId,
        int profileVersion,
        String message,
        Instant createdAt,
        boolean read,
        boolean actionRequired,
        List<String> affectedFields
) {
    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
        notification.getId(),
        notification.getPolicyId(),
        notification.getProfileVersion(),
        notification.getMessage(),
        notification.getCreatedAt(),
        notification.getReadAt() != null,
        notification.isActionRequired(),
        notification.getAffectedFields().stream()
                .map(ProfileField::label)
                .toList()
        );
    }
}
