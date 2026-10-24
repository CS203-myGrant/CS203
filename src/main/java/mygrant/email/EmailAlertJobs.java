package mygrant.email;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the alert email jobs. Set mygrant.email.jobs-enabled=false to run none of them; the
 * test suite does. Spring's single scheduler thread runs the jobs one at a time; the
 * outbox's conditional claims keep a second instance, or the admin trigger, from
 * double-sending.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "mygrant.email.jobs-enabled", havingValue = "true", matchIfMissing = true)
public class EmailAlertJobs {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmailAlertJobs.class);

    private final EmailSender sender;
    private final ImmediateAlertQueue immediateQueue;
    private final EmailDispatcher dispatcher;
    private final DailyDigestService digestService;
    private final AtomicBoolean warnedNotConfigured = new AtomicBoolean();

    public EmailAlertJobs(EmailSender sender, ImmediateAlertQueue immediateQueue, EmailDispatcher dispatcher,
            DailyDigestService digestService) {
        this.sender = sender;
        this.immediateQueue = immediateQueue;
        this.dispatcher = dispatcher;
        this.digestService = digestService;
    }

    @Scheduled(initialDelayString = "${mygrant.email.dispatch-interval:30s}",
            fixedDelayString = "${mygrant.email.dispatch-interval:30s}")
    public void sendDueEmails() {
        if (!configured()) {
            return;
        }
        try {
            immediateQueue.queueDue(Instant.now());
            dispatcher.dispatchDue(Instant.now());
        } catch (RuntimeException failure) {
            LOGGER.warn("Alert email run failed; the next run will try again", failure);
        }
    }

    /** The digest date is the local date in the digest zone when the job runs. */
    @Scheduled(cron = "${mygrant.email.digest-cron:0 0 8 * * *}", zone = "${mygrant.email.digest-zone:America/New_York}")
    public void sendDailyDigests() {
        if (!configured()) {
            return;
        }
        try {
            Instant now = Instant.now();
            int created = digestService.buildDigests(digestService.today(now), now);
            LOGGER.info("Queued {} daily digests for {}", created, digestService.today(now));
            dispatcher.dispatchDue(Instant.now());
        } catch (RuntimeException failure) {
            LOGGER.warn("Daily digest run failed; alerts it missed go in the next digest", failure);
        }
    }

    /** Without Resend settings (as in CI) the jobs do nothing at all, and say so once. */
    private boolean configured() {
        if (sender.isConfigured()) {
            return true;
        }
        if (warnedNotConfigured.compareAndSet(false, true)) {
            LOGGER.warn("Alert emails are off: set RESEND_API_KEY and RESEND_FROM_EMAIL to send them");
        }
        return false;
    }
}
