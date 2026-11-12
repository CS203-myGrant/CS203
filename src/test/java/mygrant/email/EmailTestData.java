package mygrant.email;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.test.util.ReflectionTestUtils;

import mygrant.impact.ImpactUrgency;
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
                Duration.ofHours(24), Duration.ofMinutes(10), 50, ZoneId.of("America/New_York"), Duration.ofHours(48));
    }

    static User verifiedUser(Long id) {
        User user = new User("Student " + id, "student" + id + "@example.com", null);
        ReflectionTestUtils.setField(user, "id", id);
        user.setWorkosUserId("user_workos_" + id);
        return user;
    }

    /** A HIGH-impact item: the critical email setting applies. */
    static ImpactEmailAlert critical(Long id, Long userId, Instant updatedAt) {
        return alert(id, userId, ImpactUrgency.HIGH, ImpactEmailAlert.Kind.FIRST, updatedAt);
    }

    /** A MEDIUM-impact item: the other email setting applies. */
    static ImpactEmailAlert informational(Long id, Long userId, Instant updatedAt) {
        return alert(id, userId, ImpactUrgency.MEDIUM, ImpactEmailAlert.Kind.FIRST, updatedAt);
    }

    /** A change we could not assess yet: digest only, linked to its in-app alert. */
    static ImpactEmailAlert unassessed(Long id, Long userId, Instant updatedAt) {
        ImpactEmailAlert alert = alert(id, userId, null, ImpactEmailAlert.Kind.FIRST, updatedAt);
        ReflectionTestUtils.setField(alert, "assessmentId", null);
        ReflectionTestUtils.setField(alert, "assessmentVersion", null);
        return alert;
    }

    /** Item {@code id} describes assessment {@code 100 + id}, from in-app alert {@code 200 + id}. */
    static ImpactEmailAlert alert(Long id, Long userId, ImpactUrgency urgency, ImpactEmailAlert.Kind kind,
            Instant updatedAt) {
        ImpactEmailAlert alert = org.springframework.beans.BeanUtils.instantiateClass(ImpactEmailAlert.class);
        ReflectionTestUtils.setField(alert, "id", id);
        ReflectionTestUtils.setField(alert, "userId", userId);
        ReflectionTestUtils.setField(alert, "policyId", 3L);
        ReflectionTestUtils.setField(alert, "notificationId", 200 + id);
        ReflectionTestUtils.setField(alert, "kind", kind);
        ReflectionTestUtils.setField(alert, "assessmentId", 100 + id);
        ReflectionTestUtils.setField(alert, "assessmentVersion", 1);
        ReflectionTestUtils.setField(alert, "urgency", urgency);
        ReflectionTestUtils.setField(alert, "policyTitle", "F-1 rule " + id);
        ReflectionTestUtils.setField(alert, "summary", "New policy affecting F-1: Alert " + id);
        ReflectionTestUtils.setField(alert, "createdAt", updatedAt);
        ReflectionTestUtils.setField(alert, "updatedAt", updatedAt);
        return alert;
    }

    static ImpactEmailAlert inDelivery(ImpactEmailAlert alert, UUID deliveryId) {
        ReflectionTestUtils.setField(alert, "emailDeliveryId", deliveryId);
        return alert;
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
