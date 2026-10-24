package mygrant.email;

import static mygrant.email.EmailTestData.critical;
import static mygrant.email.EmailTestData.informational;
import static mygrant.email.EmailTestData.verifiedUser;
import static mygrant.email.EmailTestData.withSeverity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import mygrant.notifications.Notification;
import mygrant.notifications.NotificationRepository;
import mygrant.user.User;
import mygrant.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class ImmediateAlertQueueTests {

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailPreferenceRepository preferenceRepository;

    @Mock
    private EmailOutbox outbox;

    @Test
    void queuesCriticalAlertsForUsersOnTheDefaultSettings() {
        Notification alert = critical(42L, 7L, NOW.minusSeconds(30));
        candidates(List.of(alert), List.of(verifiedUser(7L)));
        when(outbox.queueImmediate(7L, 42L, NOW)).thenReturn(Optional.of(UUID.randomUUID()));

        assertThat(queue().queueDue(NOW)).isEqualTo(1);
    }

    @Test
    void highImpactAlertsCountAsCritical() {
        Notification alert = withSeverity(informational(42L, 7L, NOW), "HIGH");
        candidates(List.of(alert), List.of(verifiedUser(7L)));
        when(outbox.queueImmediate(7L, 42L, NOW)).thenReturn(Optional.of(UUID.randomUUID()));

        assertThat(queue().queueDue(NOW)).isEqualTo(1);
    }

    @Test
    void leavesOtherAlertsForTheDigestByDefault() {
        candidates(List.of(informational(42L, 7L, NOW)), List.of(verifiedUser(7L)));

        assertThat(queue().queueDue(NOW)).isZero();
        verify(outbox, never()).queueImmediate(anyLong(), anyLong(), any());
    }

    @Test
    void respectsCriticalSetToDigestOrOff() {
        candidates(List.of(critical(42L, 7L, NOW), critical(43L, 8L, NOW)), List.of(verifiedUser(7L), verifiedUser(8L)));
        when(preferenceRepository.findAllById(Set.of(7L, 8L))).thenReturn(List.of(
                new EmailPreference(7L, DeliveryMode.DIGEST, DeliveryMode.DIGEST),
                new EmailPreference(8L, DeliveryMode.OFF, DeliveryMode.OFF)));

        assertThat(queue().queueDue(NOW)).isZero();
        verify(outbox, never()).queueImmediate(anyLong(), anyLong(), any());
    }

    @Test
    void queuesOtherAlertsWhenTheUserWantsThoseImmediatelyToo() {
        candidates(List.of(informational(42L, 7L, NOW)), List.of(verifiedUser(7L)));
        when(preferenceRepository.findAllById(Set.of(7L)))
                .thenReturn(List.of(new EmailPreference(7L, DeliveryMode.OFF, DeliveryMode.IMMEDIATE)));
        when(outbox.queueImmediate(7L, 42L, NOW)).thenReturn(Optional.of(UUID.randomUUID()));

        assertThat(queue().queueDue(NOW)).isEqualTo(1);
    }

    @Test
    void skipsUnverifiedInactivePurgedAndMissingAccounts() {
        User unverified = verifiedUser(7L);
        unverified.setWorkosUserId(null);
        User inactive = verifiedUser(8L);
        inactive.setActive(false);
        User purged = verifiedUser(9L);
        purged.anonymizeAndDeactivate(NOW);
        // User 10 has an alert but no row any more.
        candidates(List.of(critical(42L, 7L, NOW), critical(43L, 8L, NOW), critical(44L, 9L, NOW),
                critical(45L, 10L, NOW)), List.of(unverified, inactive, purged));

        assertThat(queue().queueDue(NOW)).isZero();
        verify(outbox, never()).queueImmediate(anyLong(), anyLong(), any());
    }

    @Test
    void onlyLooksBackOverTheConfiguredWindow() {
        when(notificationRepository.findNotEmailedSince(NOW.minus(Duration.ofHours(24)))).thenReturn(List.of());

        assertThat(queue().queueDue(NOW)).isZero();
        verify(notificationRepository).findNotEmailedSince(NOW.minus(Duration.ofHours(24)));
    }

    @Test
    void alreadyClaimedAlertsAreNotCounted() {
        candidates(List.of(critical(42L, 7L, NOW)), List.of(verifiedUser(7L)));
        when(outbox.queueImmediate(7L, 42L, NOW)).thenReturn(Optional.empty());

        assertThat(queue().queueDue(NOW)).isZero();
    }

    @Test
    void oneFailureDoesNotStopTheOthers() {
        candidates(List.of(critical(42L, 7L, NOW), critical(43L, 7L, NOW)), List.of(verifiedUser(7L)));
        when(outbox.queueImmediate(7L, 42L, NOW)).thenThrow(new IllegalStateException("database blip"));
        when(outbox.queueImmediate(7L, 43L, NOW)).thenReturn(Optional.of(UUID.randomUUID()));

        assertThat(queue().queueDue(NOW)).isEqualTo(1);
    }

    private void candidates(List<Notification> notifications, List<User> users) {
        when(notificationRepository.findNotEmailedSince(NOW.minus(Duration.ofHours(24)))).thenReturn(notifications);
        when(userRepository.findAllById(any())).thenReturn(users);
    }

    private ImmediateAlertQueue queue() {
        EmailProperties properties = EmailTestData.properties();
        return new ImmediateAlertQueue(notificationRepository, userRepository, preferenceRepository,
                new EmailEligibility(properties), outbox, properties);
    }
}
