package mygrant.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import mygrant.account.AccountPurgeService;
import mygrant.email.EmailDispatcher.Outcome;
import mygrant.notifications.Notification;
import mygrant.user.User;

/** Immediate alert emails against PostgreSQL: claims, retries and duplicate protection. */
class EmailDeliveryIntegrationTest extends EmailIntegrationTestBase {

    @Autowired AccountPurgeService purgeService;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Test
    void aProviderOutageNeverAffectsTheAlertAndTheRetrySendsItOnce() {
        User user = applicant();
        sender.failNext(FakeEmailSender.retryable("HTTP 503 service_unavailable"));

        Notification alert = publishPolicy("Outage rule", user).get(0);
        rateHigh(alert);
        UUID email = outbox.queueImmediate(user.getId(), alert.getId(), now).orElseThrow();

        // The alert exists and was pushed to open pages before email was even involved.
        verify(streams).publish(eq(user.getId()), argThat(response -> response.id().equals(alert.getId())));
        assertThat(dispatcher.dispatch(email, now)).isEqualTo(Outcome.RETRYING);
        assertThat(notifications.findById(alert.getId())).isPresent();
        assertThat(delivery(email).getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(delivery(email).getNextAttemptAt()).isEqualTo(now.plus(Duration.ofMinutes(1)));

        assertThat(dispatcher.dispatch(email, now)).isEqualTo(Outcome.NOT_CLAIMED);
        assertThat(dispatcher.dispatch(email, now.plus(Duration.ofMinutes(2)))).isEqualTo(Outcome.SENT);
        assertThat(dispatcher.dispatch(email, now.plus(Duration.ofMinutes(3)))).isEqualTo(Outcome.NOT_CLAIMED);
        assertThat(outbox.queueImmediate(user.getId(), alert.getId(), now)).isEmpty();

        EmailDelivery sent = delivery(email);
        assertThat(sent.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(sent.getAttempts()).isEqualTo(2);
        assertThat(sent.getProviderMessageId()).isEqualTo("resend-1");
        assertThat(sent.getLastError()).isNull();
        assertThat(sender.requests).hasSize(2);
        assertThat(sender.requests.get(0).idempotencyKey()).isEqualTo(email.toString());
        assertThat(sender.requests.get(1)).isEqualTo(sender.requests.get(0));
        assertThat(sender.delivered).singleElement()
                .satisfies(message -> assertThat(message.to()).isEqualTo(user.getEmail()));
    }

    @Test
    void anOverlappingRunCannotClaimAnEmailBeingSentAndIsNotBlockedByIt() throws Exception {
        User user = applicant();
        Notification alert = publishPolicy("Overlap rule", user).get(0);
        rateHigh(alert);
        UUID email = outbox.queueImmediate(user.getId(), alert.getId(), now).orElseThrow();
        CountDownLatch inSend = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        sender.duringSend(message -> {
            inSend.countDown();
            await(release);
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Outcome> first = pool.submit(() -> dispatcher.dispatch(email, now));
            assertThat(inSend.await(10, TimeUnit.SECONDS)).isTrue();

            // The first run is inside the provider call. Had it kept a transaction or row lock
            // open, this claim would block instead of returning at once.
            Outcome second = dispatcher.dispatch(email, now);
            release.countDown();

            assertThat(second).isEqualTo(Outcome.NOT_CLAIMED);
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(Outcome.SENT);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(sender.requests).hasSize(1);
        assertThat(delivery(email).getAttempts()).isEqualTo(1);
    }

    @Test
    void twoQueueRunsRacingForOneAlertMakeOneEmail() throws Exception {
        User user = applicant();
        Notification alert = publishPolicy("Race rule", user).get(0);
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Optional<UUID>>> runs = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return outbox.queueImmediate(user.getId(), alert.getId(), now);
                }));
            }
            List<UUID> queued = new ArrayList<>();
            for (Future<Optional<UUID>> run : runs) {
                run.get(10, TimeUnit.SECONDS).ifPresent(queued::add);
            }

            assertThat(queued).hasSize(1);
            assertThat(emailOf(alert)).isEqualTo(queued.get(0));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM email_deliveries WHERE user_id = ?",
                    Integer.class, user.getId())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void markingAnAlertReadKeepsItsEmailClaim() {
        User user = applicant();
        Notification alert = publishPolicy("Read rule", user).get(0);
        UUID email = outbox.queueImmediate(user.getId(), alert.getId(), now).orElseThrow();

        notificationService.markRead(user.getEmail(), alert.getId());

        assertThat(notifications.findById(alert.getId()).orElseThrow().getReadAt()).isNotNull();
        assertThat(emailOf(alert)).isEqualTo(email);
    }

    @Test
    void anAccountPurgeRemovesItsEmailsAndPreferences() {
        User user = applicant();
        Notification alert = publishPolicy("Purge rule", user).get(0);
        UUID email = outbox.queueImmediate(user.getId(), alert.getId(), now).orElseThrow();
        preferences.saveAndFlush(new EmailPreference(user.getId(), DeliveryMode.DIGEST, DeliveryMode.OFF));

        purgeService.purge(user.getId());

        assertThat(deliveries.findById(email)).isEmpty();
        assertThat(preferences.findById(user.getId())).isEmpty();
        assertThat(dispatcher.dispatch(email, now)).isEqualTo(Outcome.NOT_CLAIMED);
        assertThat(sender.requests).isEmpty();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
