package mygrant.email;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.test.util.ReflectionTestUtils;

import mygrant.notifications.Notification;
import mygrant.notifications.ProfileField;
import mygrant.user.User;

/** Builders for the email tests; entities without setters are filled in by reflection. */
final class EmailTestData {

    static final String JWT_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private EmailTestData() {
    }

    static EmailProperties properties() {
        return properties(false);
    }

    static EmailProperties properties(boolean allowUnverifiedRecipients) {
        return new EmailProperties("http://localhost:8080", allowUnverifiedRecipients, 4,
                List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15)),
                Duration.ofHours(24), Duration.ofMinutes(10), 50, ZoneId.of("America/New_York"));
    }

    static User verifiedUser(Long id) {
        User user = new User("Student " + id, "student" + id + "@example.com", null, "F-1", null, null);
        ReflectionTestUtils.setField(user, "id", id);
        user.setWorkosUserId("user_workos_" + id);
        return user;
    }

    static Notification critical(Long id, Long userId, Instant createdAt) {
        return notification(id, userId, true, createdAt);
    }

    static Notification informational(Long id, Long userId, Instant createdAt) {
        return notification(id, userId, false, createdAt);
    }

    static Notification notification(Long id, Long userId, boolean actionRequired, Instant createdAt) {
        Notification notification = new Notification(userId, 1, 3L,
                "New policy affecting F-1: Alert " + id,
                actionRequired ? List.of(ProfileField.PROGRAM_END_DATE) : List.of());
        ReflectionTestUtils.setField(notification, "id", id);
        ReflectionTestUtils.setField(notification, "createdAt", createdAt);
        return notification;
    }

    static Notification withSeverity(Notification notification, String severity) {
        ReflectionTestUtils.setField(notification, "impactSeverity", severity);
        return notification;
    }

    static Notification inDelivery(Notification notification, UUID deliveryId) {
        ReflectionTestUtils.setField(notification, "emailDeliveryId", deliveryId);
        return notification;
    }

    /** A row as a dispatcher run sees it right after claiming it. */
    static EmailDelivery claimed(UUID id, Long userId, DeliveryKind kind, LocalDate digestDate, int attempts) {
        EmailDelivery delivery = org.springframework.beans.BeanUtils.instantiateClass(EmailDelivery.class);
        ReflectionTestUtils.setField(delivery, "id", id);
        ReflectionTestUtils.setField(delivery, "userId", userId);
        ReflectionTestUtils.setField(delivery, "kind", kind);
        ReflectionTestUtils.setField(delivery, "digestDate", digestDate);
        ReflectionTestUtils.setField(delivery, "status", DeliveryStatus.SENDING);
        ReflectionTestUtils.setField(delivery, "attempts", attempts);
        return delivery;
    }
}
