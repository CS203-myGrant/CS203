package mygrant.rules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Runs the rule backfill once the application is up, on a background thread. A failure is logged
 * and never stops the application. Set mygrant.rules.backfill-on-startup=false to skip it; the
 * test suite does, and calls PolicyRuleBackfill directly.
 */
@Component
public class PolicyRuleBackfillRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(PolicyRuleBackfillRunner.class);

    private final PolicyRuleBackfill backfill;
    private final boolean enabled;

    public PolicyRuleBackfillRunner(PolicyRuleBackfill backfill,
            @Value("${mygrant.rules.backfill-on-startup:true}") boolean enabled) {
        this.backfill = backfill;
        this.enabled = enabled;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        if (!enabled) {
            return;
        }
        try {
            PolicyRuleBackfill.Result result = backfill.run();
            LOGGER.info("Rule backfill linked {} of {} unlinked documents ({} failed)",
                    result.linked(), result.candidates(), result.failed());
        } catch (RuntimeException failure) {
            LOGGER.error("Rule backfill failed; the application keeps running and the next start tries again",
                    failure);
        }
    }
}
