package mygrant.rules;

import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mygrant.policies.PolicyDocumentRepository;
import mygrant.policies.PolicyNotFoundException;
import mygrant.policies.SourceType;
import mygrant.testclock.TestClock;
import mygrant.user.User;
import mygrant.user.UserRepository;

/**
 * Clears a rule's disputed flag once a court lifts the block. Nothing else ever clears it: a
 * report of a lifted block can read like a report of litigation, so the tracker cannot tell the
 * two apart, and an admin decides instead. For the same reason, news never disputes a cleared
 * rule again: only a court order an admin records does.
 */
@Service
public class RuleDisputeService {

    private final PolicyRuleStore store;
    private final PolicyDocumentRepository policies;
    private final UserRepository users;
    private final TestClock clock;

    public RuleDisputeService(PolicyRuleStore store, PolicyDocumentRepository policies, UserRepository users,
            TestClock clock) {
        this.store = store;
        this.policies = policies;
        this.users = users;
        this.clock = clock;
    }

    /**
     * Clears the dispute of the rule policy document policyId is in, and writes an append-only
     * ADMIN step naming the admin, their reason and the source that lifted the block. The stage and
     * effective date stay, so the timeline shows the rule EFFECTIVE again once its date has come.
     * Sends no alert, raises no event and changes no other rule. From then on a report of
     * litigation in the news is held for review; a court order an admin records, dated after the
     * source, disputes the rule again.
     *
     * @throws RuleNotDisputedException when the rule is not disputed
     * @throws IllegalArgumentException when the source is dated after the admin's test-clock date,
     *         or before the report that disputed the rule
     */
    @Transactional
    public ClearDisputeResponse clear(long policyId, ClearDisputeRequest request, String adminEmail) {
        if (!policies.existsById(policyId)) {
            throw new PolicyNotFoundException(policyId);
        }
        // The same lock as tracking, so a dispute recorded at the same time is never lost.
        store.lockLinking();
        long ruleId = store.findLink(policyId).orElseThrow(() -> new PolicyRuleNotFoundException(policyId)).ruleId();
        PolicyRuleStore.Rule rule = store.findRule(ruleId).orElseThrow(() -> new PolicyRuleNotFoundException(policyId));
        RuleState before = rule.state();
        if (!before.disputed()) {
            throw new RuleNotDisputedException(ruleId);
        }

        ClearDisputeRequest.Source lifted = request.source();
        LocalDate liftedOn = lifted.publicationDate();
        LocalDate today = clock.today(users.findByEmail(adminEmail).map(User::getId).orElse(null));
        if (liftedOn.isAfter(today)) {
            throw new IllegalArgumentException("source.publicationDate " + liftedOn + " is after today, " + today);
        }
        // The timeline orders steps by source date, so a lift dated before the dispute would read as
        // coming first, and the rule would look disputed still.
        store.lastDisputedOn(ruleId).filter(liftedOn::isBefore).ifPresent(disputedOn -> {
            throw new IllegalArgumentException("source.publicationDate " + liftedOn
                    + " is before the report that disputed the rule, published " + disputedOn);
        });

        RuleState after = new RuleState(before.primaryStage(), before.effectiveDate(), false);
        RuleSource source = new RuleSource(null, lifted.externalId().trim(), SourceType.SECONDARY,
                lifted.sourceUrl().trim(), lifted.title().trim(), lifted.agency().trim(), liftedOn);
        String reason = request.reason().trim();
        store.updateRule(ruleId, after, rule.primaryAsOf());
        PolicyRuleStore.ClearedStep step = store.appendDisputeCleared(ruleId, before, after, source, adminEmail,
                reason);
        return new ClearDisputeResponse(ruleId, step.historyId(), after.primaryStage(), after.effectiveDate(), false,
                adminEmail, step.recordedAt(), reason, source);
    }
}
