package mygrant.email;

import static mygrant.email.EmailTestData.claimed;
import static mygrant.email.EmailTestData.critical;
import static mygrant.email.EmailTestData.verifiedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;

import mygrant.email.EmailDispatcher.Outcome;
import mygrant.notifications.Notification;
import mygrant.notifications.NotificationRepository;
import mygrant.user.User;
import mygrant.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class EmailDispatcherTests {

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    private static final UUID ID = UUID.fromString("6f1c2a8e-0000-4000-8000-000000000001");

    @Mock
    private EmailDeliveryRepository deliveryRepository;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailPreferenceRepository preferenceRepository;

    private final FakeEmailSender sender = new FakeEmailSender();
    private final Notification alert = critical(42L, 7L, NOW.minusSeconds(30));

    @Test
    void sendsAClaimedEmailUnderItsDeliveryIdAndRecordsIt() {
        claimedAtAttempts(1);

        assertThat(dispatcher().dispatch(ID, NOW)).isEqualTo(Outcome.SENT);

        assertThat(sender.delivered).singleElement().satisfies(message -> {
            assertThat(message.idempotencyKey()).isEqualTo(ID.toString());
            assertThat(message.to()).isEqualTo("student7@example.com");
            assertThat(message.headers()).containsKey("List-Unsubscribe");
        });
        verify(deliveryRepository).markSent(eq(ID), eq("resend-1"), any());
    }

    @Test
    void sendsNothingWhenAnotherRunHoldsTheClaim() {
        when(deliveryRepository.claim(ID, NOW)).thenReturn(0);

        assertThat(dispatcher().dispatch(ID, NOW)).isEqualTo(Outcome.NOT_CLAIMED);

        assertThat(sender.requests).isEmpty();
        verify(deliveryRepository, never()).findById(any());
    }

    @Test
    void withoutCredentialsNothingIsClaimedSoNoAttemptIsUsedUp() {
        sender.configured = false;

        assertThat(dispatcher().dispatchDue(NOW)).isEqualTo(EmailDispatcher.Result.NONE);
        assertThat(dispatcher().dispatch(ID, NOW)).isEqualTo(Outcome.NOT_CLAIMED);

        verifyNoInteractions(deliveryRepository);
        assertThat(sender.requests).isEmpty();
    }

    @Test
    void retriesAfterOneFiveAndFifteenMinutesThenFails() {
        claimedAtAttempts(1, 2, 3, 4);
        for (int i = 0; i < 4; i++) {
            sender.failNext(FakeEmailSender.retryable("HTTP 503 service_unavailable"));
        }
        EmailDispatcher dispatcher = dispatcher();

        assertThat(dispatcher.dispatch(ID, NOW)).isEqualTo(Outcome.RETRYING);
        assertThat(dispatcher.dispatch(ID, NOW)).isEqualTo(Outcome.RETRYING);
        assertThat(dispatcher.dispatch(ID, NOW)).isEqualTo(Outcome.RETRYING);
        assertThat(dispatcher.dispatch(ID, NOW)).isEqualTo(Outcome.FAILED);

        verify(deliveryRepository).markRetry(ID, NOW.plus(Duration.ofMinutes(1)), "HTTP 503 service_unavailable");
        verify(deliveryRepository).markRetry(ID, NOW.plus(Duration.ofMinutes(5)), "HTTP 503 service_unavailable");
        verify(deliveryRepository).markRetry(ID, NOW.plus(Duration.ofMinutes(15)), "HTTP 503 service_unavailable");
        verify(deliveryRepository).markFinished(ID, DeliveryStatus.FAILED, "HTTP 503 service_unavailable");
        assertThat(sender.requests).hasSize(4);
    }

    @Test
    void aRetryResendsExactlyTheSameRequest() {
        claimedAtAttempts(1, 2);
        sender.failNext(FakeEmailSender.retryable("Network error: SocketTimeoutException"));
        EmailDispatcher dispatcher = dispatcher();

        dispatcher.dispatch(ID, NOW);
        assertThat(dispatcher.dispatch(ID, NOW.plus(Duration.ofMinutes(1)))).isEqualTo(Outcome.SENT);

        assertThat(sender.requests).hasSize(2);
        assertThat(sender.requests.get(1)).isEqualTo(sender.requests.get(0));
    }

    @Test
    void authAndValidationErrorsAreNotRetried() {
        claimedAtAttempts(1);
        sender.failNext(FakeEmailSender.rejected(403, "validation_error"));

        assertThat(dispatcher().dispatch(ID, NOW)).isEqualTo(Outcome.FAILED);

        verify(deliveryRepository).markFinished(ID, DeliveryStatus.FAILED, "HTTP 403 validation_error");
        verify(deliveryRepository, never()).markRetry(any(), any(), anyString());
    }

    @Test
    void aRefusedChangedRetryIsFinal() {
        claimedAtAttempts(2);
        sender.failNext(FakeEmailSender.rejected(409, "invalid_idempotent_request"));

        assertThat(dispatcher().dispatch(ID, NOW)).isEqualTo(Outcome.FAILED);

        verify(deliveryRepository, never()).markRetry(any(), any(), anyString());
        verify(notificationRepository, never()).releaseFromDelivery(any(), any());
    }

    @Test
    void firstAttemptForAnUnverifiedUserIsSkippedAndTheAlertReleased() {
        User unverified = verifiedUser(7L);
        unverified.setWorkosUserId(null);
        claimedAtAttempts(1);
        when(userRepository.findById(7L)).thenReturn(Optional.of(unverified));

        assertThat(dispatcher().dispatch(ID, NOW)).isEqualTo(Outcome.SKIPPED);

        assertThat(sender.requests).isEmpty();
        verify(notificationRepository).releaseFromDelivery(ID, List.of(42L));
        verify(deliveryRepository).markFinished(ID, DeliveryStatus.SKIPPED, "Recipient can no longer receive email");
    }

    @Test
    void firstAttemptForAPurgedAccountIsSkipped() {
        User purged = verifiedUser(7L);
        purged.anonymizeAndDeactivate(NOW);
        claimedAtAttempts(1);
        when(userRepository.findById(7L)).thenReturn(Optional.of(purged));

        assertThat(dispatcher().dispatch(ID, NOW)).isEqualTo(Outcome.SKIPPED);
        assertThat(sender.requests).isEmpty();
    }

    @Test
    void firstAttemptAfterTheUserTurnedCriticalEmailsOffIsSkippedAndReleased() {
        claimedAtAttempts(1);
        when(preferenceRepository.findById(7L))
                .thenReturn(Optional.of(new EmailPreference(7L, DeliveryMode.OFF, DeliveryMode.DIGEST)));

        assertThat(dispatcher().dispatch(ID, NOW)).isEqualTo(Outcome.SKIPPED);

        assertThat(sender.requests).isEmpty();
        verify(notificationRepository).releaseFromDelivery(ID, List.of(42L));
    }

    @Test
    void aRetryAfterAnOptOutIsSkippedButKeepsItsAlertsClaimed() {
        // The first attempt may have reached the user; releasing the alert could email it again.
        claimedAtAttempts(2);
        when(preferenceRepository.findById(7L))
                .thenReturn(Optional.of(new EmailPreference(7L, DeliveryMode.OFF, DeliveryMode.OFF)));

        assertThat(dispatcher().dispatch(ID, NOW)).isEqualTo(Outcome.SKIPPED);

        assertThat(sender.requests).isEmpty();
        verify(notificationRepository, never()).releaseFromDelivery(any(), any());
        verify(deliveryRepository).markFinished(ID, DeliveryStatus.SKIPPED, "Turned off by the user before a retry");
    }

    @Test
    void anUnexpectedErrorAroundTheSendIsRetried() {
        when(deliveryRepository.claim(ID, NOW)).thenReturn(1);
        when(deliveryRepository.findById(ID)).thenReturn(Optional.of(claimed(ID, 7L, DeliveryKind.IMMEDIATE, null, 1)));
        when(userRepository.findById(7L)).thenReturn(Optional.of(verifiedUser(7L)));
        when(notificationRepository.findByEmailDeliveryIdOrderByCreatedAtAscIdAsc(ID))
                .thenThrow(new DataAccessResourceFailureException("connection reset"));

        assertThat(dispatcher().dispatch(ID, NOW)).isEqualTo(Outcome.RETRYING);

        verify(deliveryRepository).markRetry(ID, NOW.plus(Duration.ofMinutes(1)),
                "Unexpected DataAccessResourceFailureException");
    }

    @Test
    void dispatchDueRecoversInterruptedSendsThenSendsTheBatch() {
        UUID second = UUID.fromString("6f1c2a8e-0000-4000-8000-000000000002");
        when(deliveryRepository.findDueIds(NOW, PageRequest.of(0, 50))).thenReturn(List.of(ID, second));
        claimedAtAttempts(1);
        when(deliveryRepository.claim(second, NOW)).thenReturn(0);

        EmailDispatcher.Result result = dispatcher().dispatchDue(NOW);

        verify(deliveryRepository).failStuck(NOW.minus(Duration.ofMinutes(10)), 4);
        verify(deliveryRepository).requeueStuck(NOW.minus(Duration.ofMinutes(10)), 4, NOW);
        assertThat(result).isEqualTo(new EmailDispatcher.Result(1, 0, 0, 0));
    }

    @Test
    void oneEmailWhoseOutcomeCannotBeRecordedDoesNotStopTheBatch() {
        UUID second = UUID.fromString("6f1c2a8e-0000-4000-8000-000000000002");
        when(deliveryRepository.findDueIds(NOW, PageRequest.of(0, 50))).thenReturn(List.of(ID, second));
        when(deliveryRepository.claim(ID, NOW)).thenThrow(new DataAccessResourceFailureException("connection reset"));
        when(deliveryRepository.claim(second, NOW)).thenReturn(1);
        when(deliveryRepository.findById(second))
                .thenReturn(Optional.of(claimed(second, 7L, DeliveryKind.IMMEDIATE, null, 1)));
        when(userRepository.findById(7L)).thenReturn(Optional.of(verifiedUser(7L)));
        when(notificationRepository.findByEmailDeliveryIdOrderByCreatedAtAscIdAsc(second)).thenReturn(List.of(alert));

        assertThat(dispatcher().dispatchDue(NOW).sent()).isEqualTo(1);
    }

    /** Each dispatch call sees the row as claimed with the next attempt count. */
    private void claimedAtAttempts(Integer first, Integer... more) {
        when(deliveryRepository.claim(eq(ID), any())).thenReturn(1);
        Optional<EmailDelivery> firstRow = Optional.of(claimed(ID, 7L, DeliveryKind.IMMEDIATE, null, first));
        @SuppressWarnings("unchecked")
        Optional<EmailDelivery>[] laterRows = java.util.Arrays.stream(more)
                .map(attempts -> Optional.of(claimed(ID, 7L, DeliveryKind.IMMEDIATE, null, attempts)))
                .toArray(Optional[]::new);
        when(deliveryRepository.findById(ID)).thenReturn(firstRow, laterRows);
        org.mockito.Mockito.lenient().when(userRepository.findById(7L)).thenReturn(Optional.of(verifiedUser(7L)));
        when(notificationRepository.findByEmailDeliveryIdOrderByCreatedAtAscIdAsc(ID)).thenReturn(List.of(alert));
    }

    private EmailDispatcher dispatcher() {
        EmailProperties properties = EmailTestData.properties();
        return new EmailDispatcher(deliveryRepository, notificationRepository, userRepository, preferenceRepository,
                new EmailEligibility(properties),
                new AlertEmailRenderer(properties, new UnsubscribeTokens(EmailTestData.JWT_SECRET)),
                sender, properties);
    }
}
