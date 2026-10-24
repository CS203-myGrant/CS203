package mygrant.email;

import static mygrant.email.EmailTestData.critical;
import static mygrant.email.EmailTestData.informational;
import static mygrant.email.EmailTestData.verifiedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
class DailyDigestServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    private static final LocalDate DAY = LocalDate.parse("2026-10-24");

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailPreferenceRepository preferenceRepository;

    @Mock
    private EmailOutbox outbox;

    @Test
    void digestDateIsTheLocalDateInTheDigestZone() {
        // 03:00 UTC is still the previous evening in New York.
        assertThat(service().today(Instant.parse("2026-10-24T03:00:00Z"))).isEqualTo(LocalDate.parse("2026-10-23"));
        assertThat(service().today(NOW)).isEqualTo(DAY);
    }

    @Test
    void collectsOnlyDigestBucketAlertsIntoOneEmailPerUser() {
        // On the defaults, critical alerts go out immediately and the rest wait for the digest.
        candidates(List.of(informational(41L, 7L, NOW), critical(42L, 7L, NOW), informational(43L, 7L, NOW),
                informational(44L, 8L, NOW)), List.of(verifiedUser(7L), verifiedUser(8L)));
        when(outbox.queueDigest(7L, DAY, List.of(41L, 43L), NOW)).thenReturn(Optional.of(UUID.randomUUID()));
        when(outbox.queueDigest(8L, DAY, List.of(44L), NOW)).thenReturn(Optional.of(UUID.randomUUID()));

        assertThat(service().buildDigests(DAY, NOW)).isEqualTo(2);
    }

    @Test
    void criticalAlertsJoinTheDigestWhenTheUserChoseThat() {
        candidates(List.of(critical(42L, 7L, NOW), informational(43L, 7L, NOW)), List.of(verifiedUser(7L)));
        when(preferenceRepository.findAllById(Set.of(7L)))
                .thenReturn(List.of(new EmailPreference(7L, DeliveryMode.DIGEST, DeliveryMode.OFF)));
        when(outbox.queueDigest(7L, DAY, List.of(42L), NOW)).thenReturn(Optional.of(UUID.randomUUID()));

        assertThat(service().buildDigests(DAY, NOW)).isEqualTo(1);
    }

    @Test
    void nothingNewMeansNoEmailAndNoRow() {
        when(notificationRepository.findNotEmailedSince(NOW.minus(Duration.ofHours(48)))).thenReturn(List.of());

        assertThat(service().buildDigests(DAY, NOW)).isZero();
        verifyNoInteractions(outbox);
    }

    @Test
    void usersWithOnlyImmediateOrSwitchedOffAlertsGetNoDigest() {
        candidates(List.of(critical(42L, 7L, NOW), informational(43L, 8L, NOW)),
                List.of(verifiedUser(7L), verifiedUser(8L)));
        when(preferenceRepository.findAllById(Set.of(7L, 8L)))
                .thenReturn(List.of(new EmailPreference(8L, DeliveryMode.IMMEDIATE, DeliveryMode.OFF)));

        assertThat(service().buildDigests(DAY, NOW)).isZero();
        verify(outbox, never()).queueDigest(anyLong(), any(), anyCollection(), any());
    }

    @Test
    void unverifiedAndInactiveAccountsGetNoDigest() {
        User unverified = verifiedUser(7L);
        unverified.setWorkosUserId(null);
        User inactive = verifiedUser(8L);
        inactive.setActive(false);
        candidates(List.of(informational(42L, 7L, NOW), informational(43L, 8L, NOW)), List.of(unverified, inactive));

        assertThat(service().buildDigests(DAY, NOW)).isZero();
        verify(outbox, never()).queueDigest(anyLong(), any(), anyCollection(), any());
    }

    @Test
    void aUserWhoAlreadyHasTodaysDigestIsNotCounted() {
        candidates(List.of(informational(42L, 7L, NOW)), List.of(verifiedUser(7L)));
        when(outbox.queueDigest(7L, DAY, List.of(42L), NOW)).thenReturn(Optional.empty());

        assertThat(service().buildDigests(DAY, NOW)).isZero();
    }

    @Test
    void oneUsersFailureDoesNotStopTheOthers() {
        candidates(List.of(informational(42L, 7L, NOW), informational(43L, 8L, NOW)),
                List.of(verifiedUser(7L), verifiedUser(8L)));
        when(outbox.queueDigest(7L, DAY, List.of(42L), NOW)).thenThrow(new IllegalStateException("database blip"));
        when(outbox.queueDigest(8L, DAY, List.of(43L), NOW)).thenReturn(Optional.of(UUID.randomUUID()));

        assertThat(service().buildDigests(DAY, NOW)).isEqualTo(1);
    }

    @Test
    void buildsOneAccountsDigestFromOnlyThatAccountsAlerts() {
        Notification alert = informational(42L, 7L, NOW);
        when(userRepository.findById(7L)).thenReturn(Optional.of(verifiedUser(7L)));
        when(notificationRepository.findNotEmailedSinceForUser(7L, NOW.minus(Duration.ofHours(48))))
                .thenReturn(List.of(alert));
        UUID digest = UUID.randomUUID();
        when(outbox.queueDigest(7L, DAY, List.of(42L), NOW)).thenReturn(Optional.of(digest));

        assertThat(service().buildDigestFor(7L, DAY, NOW)).contains(digest);
        verify(notificationRepository, never()).findNotEmailedSince(any());
    }

    private void candidates(List<Notification> notifications, List<User> users) {
        when(notificationRepository.findNotEmailedSince(NOW.minus(Duration.ofHours(48)))).thenReturn(notifications);
        when(userRepository.findAllById(any())).thenReturn(users);
    }

    private DailyDigestService service() {
        EmailProperties properties = EmailTestData.properties();
        return new DailyDigestService(notificationRepository, userRepository, preferenceRepository,
                new EmailEligibility(properties), outbox, properties);
    }
}
