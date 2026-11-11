package mygrant.impact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Queue only committed policies, and never propagate background/submission failures to ingestion. */
@Component
@ConditionalOnProperty(name = "mygrant.impact.assessments-enabled", havingValue = "true", matchIfMissing = true)
public class IngestionAssessmentListener {
    private static final Logger log = LoggerFactory.getLogger(IngestionAssessmentListener.class);
    private final AsyncTaskExecutor executor;
    private final IngestionAssessmentService assessments;

    public IngestionAssessmentListener(@Qualifier("applicationTaskExecutor") AsyncTaskExecutor executor,
            IngestionAssessmentService assessments) {
        this.executor = executor;
        this.assessments = assessments;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAssessmentRequested(PolicyAssessmentRequested event) {
        try {
            executor.execute(() -> {
                try {
                    assessments.assessMatchingProfiles(event.policyId());
                } catch (RuntimeException failure) {
                    log.warn("Background assessments failed for policy {} ({})", event.policyId(),
                            failure.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException failure) {
            log.warn("Could not queue assessments for committed policy {} ({})", event.policyId(),
                    failure.getClass().getSimpleName());
        }
    }
}
