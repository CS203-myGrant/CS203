package mygrant.rules;

import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import mygrant.policies.PolicyDocumentRepository;

/**
 * Links documents stored before CS203-86, or news that matched no rule when it arrived, using the
 * same tracker as ingestion. Primary documents go first, oldest first, so their history is written
 * in order. Entries are marked BACKFILL and raise no events, so nobody is alerted about old
 * documents again. A document already in a rule is skipped, so running it twice changes nothing.
 */
@Service
public class PolicyRuleBackfill {

    private static final Logger LOGGER = LoggerFactory.getLogger(PolicyRuleBackfill.class);

    private final PolicyRuleStore store;
    private final PolicyDocumentRepository policies;
    private final PolicyRuleTracker tracker;
    private final TransactionTemplate transaction;

    public PolicyRuleBackfill(PolicyRuleStore store, PolicyDocumentRepository policies, PolicyRuleTracker tracker,
            PlatformTransactionManager transactionManager) {
        this.store = store;
        this.policies = policies;
        this.tracker = tracker;
        // Joins a caller's transaction; at startup there is none, so each document commits alone
        // and one failure leaves the rest linked.
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** How many unlinked documents were found, how many joined or started a rule, and how many failed. */
    public record Result(int candidates, int linked, int failed) {
    }

    public Result run() {
        List<Long> ids = store.unlinkedDocumentIds();
        int linked = 0;
        int failed = 0;
        for (Long id : ids) {
            try {
                Boolean joined = transaction.execute(status -> policies.findById(id)
                        .map(document -> tracker.track(document, Set.of(), RecordedBy.BACKFILL).linked())
                        .orElse(false));
                if (Boolean.TRUE.equals(joined)) {
                    linked++;
                }
            } catch (RuntimeException failure) {
                failed++;
                LOGGER.warn("Rule backfill could not link policy document {}", id, failure);
            }
        }
        return new Result(ids.size(), linked, failed);
    }
}
