package mygrant.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import mygrant.notifications.NotificationRepository;

@ExtendWith(MockitoExtension.class)
class EmailOutboxTests {

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    private static final LocalDate DAY = LocalDate.parse("2026-10-24");

    @Mock
    private EmailDeliveryRepository deliveryRepository;

    @Mock
    private NotificationRepository notificationRepository;

    @Test
    void queuesOneEmailHoldingTheAlert() {
        when(notificationRepository.claimForDelivery(any(), eq(List.of(42L)))).thenReturn(1);

        Optional<UUID> queued = new EmailOutbox(deliveryRepository, notificationRepository).queueImmediate(7L, 42L, NOW);

        ArgumentCaptor<UUID> inserted = ArgumentCaptor.forClass(UUID.class);
        verify(deliveryRepository).insertImmediate(inserted.capture(), eq(7L), eq(NOW));
        verify(notificationRepository).claimForDelivery(inserted.getValue(), List.of(42L));
        assertThat(queued).contains(inserted.getValue());
        verify(deliveryRepository, never()).deleteById(any());
    }

    @Test
    void dropsItsEmailWhenAnotherRunAlreadyClaimedTheAlert() {
        when(notificationRepository.claimForDelivery(any(), eq(List.of(42L)))).thenReturn(0);

        Optional<UUID> queued = new EmailOutbox(deliveryRepository, notificationRepository).queueImmediate(7L, 42L, NOW);

        ArgumentCaptor<UUID> inserted = ArgumentCaptor.forClass(UUID.class);
        verify(deliveryRepository).insertImmediate(inserted.capture(), eq(7L), eq(NOW));
        verify(deliveryRepository).deleteById(inserted.getValue());
        assertThat(queued).isEmpty();
    }

    @Test
    void queuesADigestHoldingTheFreeAlerts() {
        when(deliveryRepository.insertDigest(any(), eq(7L), eq(DAY), eq(NOW))).thenReturn(1);
        when(notificationRepository.claimForDelivery(any(), eq(List.of(41L, 42L)))).thenReturn(2);

        Optional<UUID> queued = outbox().queueDigest(7L, DAY, List.of(41L, 42L), NOW);

        ArgumentCaptor<UUID> inserted = ArgumentCaptor.forClass(UUID.class);
        verify(deliveryRepository).insertDigest(inserted.capture(), eq(7L), eq(DAY), eq(NOW));
        verify(notificationRepository).claimForDelivery(inserted.getValue(), List.of(41L, 42L));
        assertThat(queued).contains(inserted.getValue());
    }

    @Test
    void aSecondDigestForTheSameDateIsNotCreated() {
        when(deliveryRepository.insertDigest(any(), eq(7L), eq(DAY), eq(NOW))).thenReturn(0);

        assertThat(outbox().queueDigest(7L, DAY, List.of(42L), NOW)).isEmpty();

        verify(notificationRepository, never()).claimForDelivery(any(), any());
    }

    @Test
    void aDigestWhoseAlertsWereAllTakenIsDropped() {
        when(deliveryRepository.insertDigest(any(), eq(7L), eq(DAY), eq(NOW))).thenReturn(1);
        when(notificationRepository.claimForDelivery(any(), eq(List.of(42L)))).thenReturn(0);

        assertThat(outbox().queueDigest(7L, DAY, List.of(42L), NOW)).isEmpty();

        verify(deliveryRepository).deleteById(any());
    }

    private EmailOutbox outbox() {
        return new EmailOutbox(deliveryRepository, notificationRepository);
    }
}
