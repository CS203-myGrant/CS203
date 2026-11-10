package mygrant.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import mygrant.email.EmailDispatcher.Outcome;
import mygrant.notifications.Notification;
import mygrant.user.User;

/**
 * The alert email path end to end against PostgreSQL: an indexed policy creates alerts, the
 * critical one is emailed straight away, the rest arrive once in the next digest, and the
 * unsubscribe link in those emails stops anything still queued.
 */
class EmailAlertsEndToEndTest extends EmailIntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired ImmediateAlertQueue immediateQueue;
    @Autowired DailyDigestService digests;
    @Autowired UnsubscribeTokens tokens;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Test
    void criticalAlertGoesOutAtOnceAndTheRestOnceInTheDigest() {
        User user = applicant();
        Notification notice = publishPolicy("Fee schedule notice", user).get(0);
        Notification urgent = publishPolicy("Status change rule", user).get(0);
        rateHigh(urgent);

        // Immediate: only the critical alert, and running the job again queues nothing more.
        assertThat(immediateQueue.queueDueFor(user.getId(), now)).isEqualTo(1);
        assertThat(immediateQueue.queueDueFor(user.getId(), now)).isZero();
        UUID immediate = emailOf(urgent);
        assertThat(dispatcher.dispatch(immediate, now)).isEqualTo(Outcome.SENT);
        assertThat(emailOf(notice)).isNull();

        // Digest: one per date, and a day with nothing new creates no email and no row.
        LocalDate today = digests.today(now);
        UUID digest = digests.buildDigestFor(user.getId(), today, now).orElseThrow();
        assertThat(digests.buildDigestFor(user.getId(), today, now)).isEmpty();
        assertThat(dispatcher.dispatch(digest, now)).isEqualTo(Outcome.SENT);
        Instant tomorrow = now.plus(Duration.ofDays(1));
        assertThat(digests.buildDigestFor(user.getId(), digests.today(tomorrow), tomorrow)).isEmpty();
        assertThat(immediateQueue.queueDueFor(user.getId(), tomorrow)).isZero();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_deliveries WHERE user_id = ?",
                Integer.class, user.getId())).isEqualTo(2);
        assertThat(delivery(digest).getDigestDate()).isEqualTo(today);
        assertThat(sender.delivered).hasSize(2);
        EmailMessage alertEmail = sender.delivered.get(0);
        assertThat(alertEmail.idempotencyKey()).isEqualTo(immediate.toString());
        assertThat(alertEmail.to()).isEqualTo(user.getEmail());
        assertThat(alertEmail.text()).contains("Status change rule")
                .contains("#notification=" + urgent.getId())
                .doesNotContain("Fee schedule notice");
        EmailMessage digestEmail = sender.delivered.get(1);
        assertThat(digestEmail.subject()).endsWith(": 1 new alert");
        assertThat(digestEmail.text()).contains("Fee schedule notice")
                .contains("#notification=" + notice.getId())
                .doesNotContain("Status change rule");
    }

    @Test
    void theUnsubscribeLinkStopsADigestAlreadyQueuedAndOnlyForThatAccount() throws Exception {
        User reader = applicant();
        User other = applicant();
        Notification notice = publishPolicy("Reporting notice", reader, other).get(0);
        UUID digest = digests.buildDigestFor(reader.getId(), digests.today(now), now).orElseThrow();
        String link = "/api/v1/email/unsubscribe?token=" + tokens.issue(reader.getId());

        // Opening the link, as a mail scanner would, changes nothing.
        mvc.perform(get(link)).andExpect(status().isOk());
        assertThat(preferences.findById(reader.getId())).isEmpty();
        mvc.perform(post(link).contentType(MediaType.APPLICATION_FORM_URLENCODED).content("List-Unsubscribe=One-Click"))
                .andExpect(status().isOk());

        assertThat(dispatcher.dispatch(digest, now)).isEqualTo(Outcome.SKIPPED);
        assertThat(sender.requests).isEmpty();
        // Never attempted, so the alert is released rather than marked as emailed.
        assertThat(emailOf(notice)).isNull();
        assertThat(delivery(digest).getStatus()).isEqualTo(DeliveryStatus.SKIPPED);
        Instant tomorrow = now.plus(Duration.ofDays(1));
        assertThat(digests.buildDigestFor(reader.getId(), digests.today(tomorrow), tomorrow)).isEmpty();

        EmailPreference turnedOff = preferences.findById(reader.getId()).orElseThrow();
        assertThat(turnedOff.getCriticalDelivery()).isEqualTo(DeliveryMode.OFF);
        assertThat(turnedOff.getOtherDelivery()).isEqualTo(DeliveryMode.OFF);
        assertThat(preferences.findById(other.getId())).isEmpty();
        assertThat(digests.buildDigestFor(other.getId(), digests.today(now), now)).isPresent();
    }

    @Test
    void unverifiedAccountsAreNeverEmailed() {
        User user = applicant();
        user.setWorkosUserId(null);
        users.saveAndFlush(user);
        Notification urgent = publishPolicy("Unverified rule", user).get(0);
        rateHigh(urgent);
        publishPolicy("Unverified notice", user);

        assertThat(immediateQueue.queueDueFor(user.getId(), now)).isZero();
        assertThat(digests.buildDigestFor(user.getId(), digests.today(now), now)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_deliveries WHERE user_id = ?",
                Integer.class, user.getId())).isZero();
    }
}
