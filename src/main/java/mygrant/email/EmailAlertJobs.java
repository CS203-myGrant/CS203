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
    private final AtomicBoolean warnedNotConfigured = new AtomicBoolean();

    public EmailAlertJobs(EmailSender sender, ImmediateAlertQueue immediateQueue, EmailDispatcher dispatcher) {
        this.sender = sender;
        this.immediateQueue = immediateQueue;
        this.dispatcher = dispatcher;
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
