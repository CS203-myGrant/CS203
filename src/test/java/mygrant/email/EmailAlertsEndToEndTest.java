package mygrant.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import mygrant.email.EmailDispatcher.Outcome;
import mygrant.impact.ImpactReviewController;
import mygrant.impact.ImpactReviewService;
import mygrant.impact.ImpactUrgency;
import mygrant.notifications.Notification;
import mygrant.user.User;
import mygrant.user.UserRole;

/**
 * CS203-91 end to end against PostgreSQL: alert emails follow the AI assessment's severity. A
 * visible HIGH assessment is emailed straight away, MEDIUM and LOW wait for the digest,
 * NOT_APPLICABLE and held assessments send nothing, a change we could not assess yet goes in the
 * digest, and a later HIGH sends one more email. The unsubscribe link stops anything queued.
 */
class EmailAlertsEndToEndTest extends EmailIntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired ImmediateAlertQueue immediateQueue;
    @Autowired DailyDigestService digests;
    @Autowired UnsubscribeTokens tokens;
    @Autowired ImpactReviewService review;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Test
    void highGoesOutAtOnceMediumWaitsForTheDigestAndNotApplicableIsNeverEmailed() {
        User user = applicant();
        Notification high = publishPolicy("Status change rule", user).get(0);
        Notification medium = publishPolicy("Fee schedule notice", user).get(0);
        Notification irrelevant = publishPolicy("Rail cargo rule", user).get(0);
        long highAssessment = assess(high, ImpactUrgency.HIGH, "Your status may end before your program does.");
        assess(medium, ImpactUrgency.MEDIUM, "Fees for your next filing go up.");
        assess(irrelevant, ImpactUrgency.NOT_APPLICABLE, "This rule does not apply to you.");

        // Immediate: only the HIGH one, and running the job again queues nothing more.
        assertThat(immediateQueue.queueDueFor(user.getId(), now)).isEqualTo(1);
        assertThat(immediateQueue.queueDueFor(user.getId(), now)).isZero();
        UUID immediate = emailOf(high);
        assertThat(dispatcher.dispatch(immediate, now)).isEqualTo(Outcome.SENT);
        assertThat(emailOf(medium)).isNull();

        // Digest: the MEDIUM one only, once per date.
        LocalDate today = digests.today(now);
        UUID digest = digests.buildDigestFor(user.getId(), today, now).orElseThrow();
        assertThat(digests.buildDigestFor(user.getId(), today, now)).isEmpty();
        assertThat(dispatcher.dispatch(digest, now)).isEqualTo(Outcome.SENT);
        Instant tomorrow = now.plus(Duration.ofDays(1));
        assertThat(digests.buildDigestFor(user.getId(), digests.today(tomorrow), tomorrow)).isEmpty();
        assertThat(immediateQueue.queueDueFor(user.getId(), tomorrow)).isZero();

        assertThat(item(irrelevant, ImpactEmailAlert.Kind.FIRST)).isEmpty();
        assertThat(sender.delivered).hasSize(2);
        EmailMessage alertEmail = sender.delivered.get(0);
        assertThat(alertEmail.idempotencyKey()).isEqualTo(immediate.toString());
        assertThat(alertEmail.to()).isEqualTo(user.getEmail());
        assertThat(alertEmail.subject()).isEqualTo("High impact: Status change rule");
        assertThat(alertEmail.text()).contains("Your status may end before your program does.")
                .contains("#impact=" + highAssessment)
                // The plain summary and a link, never the full analysis.
                .doesNotContain("Why it applies").doesNotContain("Check the official source")
                .doesNotContain("Fee schedule notice");
        EmailMessage digestEmail = sender.delivered.get(1);
        assertThat(digestEmail.subject()).endsWith(": 1 new alert");
        assertThat(digestEmail.text()).contains("Medium impact").contains("Fees for your next filing go up.")
                .doesNotContain("Status change rule").doesNotContain("Rail cargo rule");
    }

    @Test
    void aHeldAssessmentSendsNothingUntilAReviewerApprovesIt() {
        User user = applicant();
        Notification alert = publishPolicy("Held rule", user).get(0);
        long held = hold(alert, ImpactUrgency.HIGH, "Draft the reviewer has not checked.");

        assertThat(immediateQueue.queueDueFor(user.getId(), now)).isZero();
        assertThat(digests.buildDigestFor(user.getId(), digests.today(now), now)).isEmpty();
        assertThat(items.findAll()).noneMatch(item -> item.getUserId().equals(user.getId()));

        review.publishCorrection(held, admin().getEmail(), new ImpactReviewController.Correction(
                "HIGH", 90, "Checked: your status may end early.", List.of("Checked"), List.of("Act now"), List.of()));

        assertThat(immediateQueue.queueDueFor(user.getId(), now)).isEqualTo(1);
        assertThat(dispatcher.dispatch(emailOf(alert), now)).isEqualTo(Outcome.SENT);
        assertThat(sender.delivered).singleElement().satisfies(message -> assertThat(message.text())
                .contains("Checked: your status may end early.").doesNotContain("Draft the reviewer"));
    }

    @Test
    void aCorrectionToHighAfterTheDigestSendsOneMoreEmailAndNoMore() {
        User user = applicant();
        Notification alert = publishPolicy("Corrected rule", user).get(0);
        long assessment = assess(alert, ImpactUrgency.MEDIUM, "Probably fine.");
        UUID digest = digests.buildDigestFor(user.getId(), digests.today(now), now).orElseThrow();
        assertThat(dispatcher.dispatch(digest, now)).isEqualTo(Outcome.SENT);

        review.publishCorrection(assessment, admin().getEmail(), new ImpactReviewController.Correction(
                "HIGH", 95, "Corrected: act before your trip.", List.of(), List.of("Act now"), List.of()));

        assertThat(immediateQueue.queueDueFor(user.getId(), now)).isEqualTo(1);
        ImpactEmailAlert escalation = item(alert, ImpactEmailAlert.Kind.ESCALATION).orElseThrow();
        assertThat(dispatcher.dispatch(escalation.getEmailDeliveryId(), now)).isEqualTo(Outcome.SENT);
        assertThat(sender.delivered.getLast().subject()).isEqualTo("Now high impact: Corrected rule");
        assertThat(sender.delivered.getLast().text()).contains("Corrected: act before your trip.");

        // Another correction, still HIGH, sends nothing more: one FIRST and one ESCALATION at most.
        review.publishCorrection(assessment, admin().getEmail(), new ImpactReviewController.Correction(
                "HIGH", 99, "Corrected again.", List.of(), List.of("Act now"), List.of()));
        assertThat(immediateQueue.queueDueFor(user.getId(), now)).isZero();
        assertThat(digests.buildDigestFor(user.getId(), digests.today(now.plus(Duration.ofDays(1))),
                now.plus(Duration.ofDays(1)))).isEmpty();
        assertThat(sender.delivered).hasSize(2);
    }

    @Test
    void aCorrectionBeforeTheDigestReplacesTheQueuedItemInsteadOfAddingOne() {
        User user = applicant();
        Notification alert = publishPolicy("Quickly corrected rule", user).get(0);
        long assessment = assess(alert, ImpactUrgency.MEDIUM, "Probably fine.");
        assertThat(immediateQueue.queueDueFor(user.getId(), now)).isZero();

        review.publishCorrection(assessment, admin().getEmail(), new ImpactReviewController.Correction(
                "HIGH", 95, "Corrected before the digest.", List.of(), List.of("Act now"), List.of()));

        assertThat(immediateQueue.queueDueFor(user.getId(), now)).isEqualTo(1);
        assertThat(item(alert, ImpactEmailAlert.Kind.ESCALATION)).isEmpty();
        assertThat(item(alert, ImpactEmailAlert.Kind.FIRST).orElseThrow().getUrgency()).isEqualTo(ImpactUrgency.HIGH);
        assertThat(digests.buildDigestFor(user.getId(), digests.today(now), now)).isEmpty();
    }

    @Test
    void concurrentRunsNeverSendTwoImmediateEmailsForOneChange() throws Exception {
        User user = applicant();
        Notification alert = publishPolicy("Race rule", user).get(0);
        assess(alert, ImpactUrgency.HIGH, "Act now.");
        CyclicBarrier start = new CyclicBarrier(3);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<Integer>> runs = new java.util.ArrayList<>();
            for (int i = 0; i < 3; i++) {
                runs.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return immediateQueue.queueDueFor(user.getId(), now);
                }));
            }
            int queued = 0;
            for (Future<Integer> run : runs) {
                queued += run.get(10, TimeUnit.SECONDS);
            }
            assertThat(queued).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_deliveries WHERE user_id = ?",
                Integer.class, user.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM impact_email_alerts WHERE user_id = ?",
                Integer.class, user.getId())).isEqualTo(1);
    }

    @Test
    void aNegatedKeywordMentionWithALowAssessmentSendsNoImmediateEmail() {
        User user = applicant();
        // The keyword rules flag the in-app alert as needing action, but the assessment is LOW.
        Notification alert = publishPolicy("This rule does not require F-1 students to act", user).get(0);
        requireAction(alert);
        assess(alert, ImpactUrgency.LOW, "Nothing changes for you.");

        assertThat(immediateQueue.queueDueFor(user.getId(), now)).isZero();
        assertThat(notifications.findById(alert.getId()).orElseThrow().isActionRequired()).isTrue();
        UUID digest = digests.buildDigestFor(user.getId(), digests.today(now), now).orElseThrow();
        assertThat(dispatcher.dispatch(digest, now)).isEqualTo(Outcome.SENT);
        assertThat(sender.delivered).singleElement()
                .satisfies(message -> assertThat(message.text()).contains("Low impact"));
    }

    @Test
    void aChangeWeCouldNotAssessGoesInTheDigestAndALaterHighIsEmailedThen() {
        User user = applicant();
        Notification alert = publishPolicy("Unassessed rule", user).get(0);

        // No assessment yet (or the AI was unavailable): never an immediate email.
        assertThat(immediateQueue.queueDueFor(user.getId(), now)).isZero();
        UUID digest = digests.buildDigestFor(user.getId(), digests.today(now), now).orElseThrow();
        assertThat(dispatcher.dispatch(digest, now)).isEqualTo(Outcome.SENT);
        assertThat(sender.delivered.getLast().text()).contains("We couldn't assess this one yet")
                .contains("#notification=" + alert.getId());

        // A later retry produces a visible HIGH: that is emailed straight away.
        long assessment = assess(alert, ImpactUrgency.HIGH, "Retried: act now.");
        assertThat(immediateQueue.queueDueFor(user.getId(), now)).isEqualTo(1);
        UUID escalation = item(alert, ImpactEmailAlert.Kind.ESCALATION).orElseThrow().getEmailDeliveryId();
        assertThat(dispatcher.dispatch(escalation, now)).isEqualTo(Outcome.SENT);
        assertThat(sender.delivered.getLast().text()).contains("Retried: act now.").contains("#impact=" + assessment);
    }

    @Test
    void theUsersSettingsDecideHowHighImpactIsEmailed() {
        User digestUser = applicant();
        User offUser = applicant();
        preferences.saveAndFlush(new EmailPreference(digestUser.getId(), DeliveryMode.DIGEST, DeliveryMode.DIGEST));
        preferences.saveAndFlush(new EmailPreference(offUser.getId(), DeliveryMode.OFF, DeliveryMode.OFF));
        List<Notification> alerts = publishPolicy("Settings rule", digestUser, offUser);
        assess(alerts.get(0), ImpactUrgency.HIGH, "Act now.");
        assess(alerts.get(1), ImpactUrgency.HIGH, "Act now.");

        assertThat(immediateQueue.queueDueFor(digestUser.getId(), now)).isZero();
        assertThat(immediateQueue.queueDueFor(offUser.getId(), now)).isZero();
        assertThat(digests.buildDigestFor(digestUser.getId(), digests.today(now), now)).isPresent();
        assertThat(digests.buildDigestFor(offUser.getId(), digests.today(now), now)).isEmpty();
    }

    @Test
    void theUnsubscribeLinkStopsADigestAlreadyQueuedAndOnlyForThatAccount() throws Exception {
        User reader = applicant();
        User other = applicant();
        List<Notification> alerts = publishPolicy("Reporting notice", reader, other);
        Notification notice = alerts.get(0);
        assess(notice, ImpactUrgency.MEDIUM, "Report your address change.");
        assess(alerts.get(1), ImpactUrgency.MEDIUM, "Report your address change.");
        UUID digest = digests.buildDigestFor(reader.getId(), digests.today(now), now).orElseThrow();
        String link = "/api/v1/email/unsubscribe?token=" + tokens.issue(reader.getId());

        // Opening the link, as a mail scanner would, changes nothing.
        mvc.perform(get(link)).andExpect(status().isOk());
        assertThat(preferences.findById(reader.getId())).isEmpty();
        mvc.perform(post(link).contentType(MediaType.APPLICATION_FORM_URLENCODED).content("List-Unsubscribe=One-Click"))
                .andExpect(status().isOk());

        assertThat(dispatcher.dispatch(digest, now)).isEqualTo(Outcome.SKIPPED);
        assertThat(sender.requests).isEmpty();
        // Never attempted, so the item is released rather than marked as emailed.
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
        assess(urgent, ImpactUrgency.HIGH, "Act now.");
        publishPolicy("Unverified notice", user);

        assertThat(immediateQueue.queueDueFor(user.getId(), now)).isZero();
        assertThat(digests.buildDigestFor(user.getId(), digests.today(now), now)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_deliveries WHERE user_id = ?",
                Integer.class, user.getId())).isZero();
    }

    private User admin() {
        User admin = applicant();
        admin.setRole(UserRole.ADMIN);
        return users.saveAndFlush(admin);
    }
}
