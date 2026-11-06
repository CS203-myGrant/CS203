package mygrant.rules;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mygrant.policies.PolicyDocumentRepository;
import mygrant.policies.PolicyNotFoundException;
import mygrant.policies.PolicyStatus;
import mygrant.rules.PolicyRuleStore.HistoryRow;
import mygrant.rules.RuleTimelineResponse.Document;
import mygrant.rules.RuleTimelineResponse.Step;
import mygrant.testclock.TestClock;
import mygrant.user.User;
import mygrant.user.UserRepository;

/**
 * Builds a rule's timeline for a reader. Each step's previous values come from the step before
 * it in timeline order, not from what was stored: an older document that arrived after a newer
 * one was stored against the newer state, which is kept as an audit record but would read wrongly
 * here. EFFECTIVE is worked out from the reader's test-clock date and added as a derived step.
 */
@Service
public class RuleTimelineService {

    private final PolicyRuleStore store;
    private final PolicyDocumentRepository policies;
    private final UserRepository users;
    private final TestClock clock;

    public RuleTimelineService(PolicyRuleStore store, PolicyDocumentRepository policies, UserRepository users,
            TestClock clock) {
        this.store = store;
        this.policies = policies;
        this.users = users;
        this.clock = clock;
    }

    /** The timeline of the rule the policy document belongs to, for the signed-in reader. */
    @Transactional(readOnly = true)
    public RuleTimelineResponse timeline(long policyId, String readerEmail) {
        if (!policies.existsById(policyId)) {
            throw new PolicyNotFoundException(policyId);
        }
        long ruleId = store.findLink(policyId).orElseThrow(() -> new PolicyRuleNotFoundException(policyId)).ruleId();
        PolicyRuleStore.Rule rule = store.findRule(ruleId).orElseThrow(() -> new PolicyRuleNotFoundException(policyId));
        LocalDate asOf = clock.today(users.findByEmail(readerEmail).map(User::getId).orElse(null));

        List<Step> steps = steps(store.history(ruleId), rule.state(), asOf);
        RuleState state = rule.state();
        PolicyStatus currentStage = tookEffect(state, asOf) ? PolicyStatus.EFFECTIVE : state.primaryStage();
        RuleSource disputedBy = steps.stream()
                .filter(step -> step.changes().contains(RuleChange.DISPUTED) && step.disputed())
                .map(Step::source).findFirst().orElse(null);
        List<RuleIdentifier> identifiers = List.copyOf(
                store.primaryIdentifiers(Set.of(ruleId)).getOrDefault(ruleId, Set.of()));
        List<Document> documents = store.documents(ruleId).stream()
                .map(document -> new Document(document.link().policyId(), document.externalId(),
                        document.sourceType(), document.title(), document.publishedOn(), document.link().method(),
                        document.link().confidence(), document.link().matchedOn(), document.link().needsReview(),
                        document.link().reviewReason()))
                .toList();
        return new RuleTimelineResponse(ruleId, rule.title(), state.primaryStage(), currentStage, asOf,
                state.effectiveDate(), state.disputed(), disputedBy, identifiers, documents, steps);
    }

    /**
     * The stored history as ordered steps, each against the one before. The stage only moves
     * forward, a step without an effective date keeps the one before, and disputed never clears;
     * the same rules the tracker applies. A TOOK_EFFECT step is added when the rule is final and
     * its effective date is on or before asOf, after the step that set that date.
     */
    static List<Step> steps(List<HistoryRow> rows, RuleState current, LocalDate asOf) {
        List<Step> steps = new ArrayList<>();
        RuleState state = null;
        for (HistoryRow row : rows) {
            RuleState stored = row.state();
            RuleState after = state == null ? stored : new RuleState(
                    RuleStages.next(state.primaryStage(), stored.primaryStage()),
                    stored.effectiveDate() != null ? stored.effectiveDate() : state.effectiveDate(),
                    state.disputed() || stored.disputed());
            steps.add(new Step(row.source().publishedOn(), row.recordedAt(), false, after.primaryStage(),
                    state == null ? null : state.primaryStage(), after.effectiveDate(),
                    state == null ? null : state.effectiveDate(), after.disputed(),
                    List.copyOf(RuleChange.between(state, after)), row.source()));
            state = after;
        }
        if (tookEffect(current, asOf)) {
            insertTookEffect(steps, current.effectiveDate());
        }
        return steps;
    }

    private static boolean tookEffect(RuleState state, LocalDate asOf) {
        return state.primaryStage() == PolicyStatus.FINAL && state.effectiveDate() != null
                && !asOf.isBefore(state.effectiveDate());
    }

    private static void insertTookEffect(List<Step> steps, LocalDate effectiveDate) {
        int setter = -1;
        for (int index = 0; index < steps.size(); index++) {
            if (effectiveDate.equals(steps.get(index).effectiveDate())
                    && steps.get(index).changes().contains(RuleChange.EFFECTIVE_DATE)) {
                setter = index;
            }
        }
        if (setter < 0) {
            return;
        }
        // After the step that announced the date, and after anything else published by that date.
        LocalDate placedAfter = steps.get(setter).observedOn().isAfter(effectiveDate)
                ? steps.get(setter).observedOn() : effectiveDate;
        int position = setter + 1;
        while (position < steps.size() && !steps.get(position).observedOn().isAfter(placedAfter)) {
            position++;
        }
        Step before = steps.get(position - 1);
        steps.add(position, new Step(effectiveDate, null, true, PolicyStatus.FINAL, before.primaryStage(),
                effectiveDate, before.effectiveDate(), before.disputed(), List.of(RuleChange.TOOK_EFFECT),
                steps.get(setter).source()));
    }
}
