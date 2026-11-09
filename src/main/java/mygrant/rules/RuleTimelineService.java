package mygrant.rules;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mygrant.policies.PolicyDocumentRepository;
import mygrant.policies.PolicyNotFoundException;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;
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
 * here. EFFECTIVE is worked out from the reader's test-clock date and added as a derived step,
 * but never for a disputed rule: a court may have blocked it, as it did the D/S rule (2026-14439)
 * the day before its effective date, and no Federal Register document records that.
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
        boolean passedWhileDisputed = effectiveDatePassedWhileDisputed(state, asOf);
        // The report behind the current dispute: the newest that set it, since an admin may have
        // cleared an earlier one.
        RuleSource disputedBy = !state.disputed() ? null : steps.stream()
                .filter(step -> step.changes().contains(RuleChange.DISPUTED) && step.disputed())
                .reduce((earlier, later) -> later).map(Step::source).orElse(null);
        List<RuleIdentifier> identifiers = List.copyOf(
                store.primaryIdentifiers(Set.of(ruleId)).getOrDefault(ruleId, Set.of()));
        List<Document> documents = store.documents(ruleId).stream()
                .map(document -> new Document(document.link().policyId(), document.externalId(),
                        document.sourceType(), document.title(), document.publishedOn(), document.link().method(),
                        document.link().confidence(), document.link().matchedOn(), document.link().needsReview(),
                        document.link().reviewReason()))
                .toList();
        return new RuleTimelineResponse(ruleId, rule.title(), state.primaryStage(), currentStage, asOf,
                state.effectiveDate(), state.disputed(), passedWhileDisputed, disputedBy, identifiers, documents,
                steps);
    }

    /**
     * The stored history as ordered steps, each against the one before. The stage only moves
     * forward and a step without an effective date keeps the one before; the same rules the tracker
     * applies. Disputed is set by a secondary report and cleared only by an admin's step; a primary
     * document leaves it as it was. A secondary report that was not applied, held for review
     * because an admin had cleared the dispute, is not a step: the rule's documents list it,
     * flagged, like every other document held for review. A final document on a rule already final
     * that is not about the effective date is a separate final rule: an ADDITIONAL_FINAL_RULE step
     * that shows its own date in ownEffectiveDate and keeps the rule's. This is worked out here, in
     * publication order, so it holds even when the second final rule was imported first. A
     * TOOK_EFFECT step is added when the rule is final, not disputed, and its effective date is on
     * or before asOf, after the step that set that date; if the rule was disputed when the date
     * came, after the step that cleared the dispute instead.
     */
    static List<Step> steps(List<HistoryRow> rows, RuleState current, LocalDate asOf) {
        List<Step> steps = new ArrayList<>();
        RuleState state = null;
        for (HistoryRow row : rows) {
            if (row.source().sourceType() == SourceType.SECONDARY && !row.applied()) {
                continue; // Held for review: it changed nothing, and the documents list it, flagged.
            }
            RuleState stored = row.state();
            // A news row stores the rule's stage, so only a primary row can be a final rule.
            boolean additional = state != null && row.source().sourceType() == SourceType.PRIMARY
                    && RuleStages.isAdditionalFinalRule(state.primaryStage(), stored.primaryStage(),
                            row.aboutEffectiveDate());
            RuleState after;
            if (state == null) {
                after = stored;
            } else if (row.recordedBy() == RecordedBy.ADMIN) {
                // An admin's step only clears the dispute: the stage and date stay the documents'.
                after = new RuleState(state.primaryStage(), state.effectiveDate(), stored.disputed());
            } else if (additional) {
                after = new RuleState(state.primaryStage(), state.effectiveDate(), disputedAfter(state, row));
            } else {
                after = new RuleState(RuleStages.next(state.primaryStage(), stored.primaryStage()),
                        stored.effectiveDate() != null ? stored.effectiveDate() : state.effectiveDate(),
                        disputedAfter(state, row));
            }
            Set<RuleChange> changes = EnumSet.noneOf(RuleChange.class);
            changes.addAll(RuleChange.between(state, after));
            if (additional) {
                changes.add(RuleChange.ADDITIONAL_FINAL_RULE);
            }
            steps.add(new Step(row.source().publishedOn(), row.recordedAt(), false, after.primaryStage(),
                    state == null ? null : state.primaryStage(), after.effectiveDate(),
                    state == null ? null : state.effectiveDate(), additional ? stored.effectiveDate() : null,
                    after.disputed(), List.copyOf(changes), row.source()));
            state = after;
        }
        if (tookEffect(current, asOf)) {
            insertTookEffect(steps, current.effectiveDate());
        }
        return steps;
    }

    /**
     * Whether the rule is disputed after a document's row: a secondary report of litigation sets
     * it. A primary row stores the flag the rule had when the row was written, which may belong to
     * a dispute that, in publication order, an admin's step has already cleared, so it leaves the
     * flag as it was, as the tracker does.
     */
    private static boolean disputedAfter(RuleState before, HistoryRow row) {
        if (row.source().sourceType() == SourceType.PRIMARY) {
            return before.disputed();
        }
        return before.disputed() || row.state().disputed();
    }

    /** A final rule whose effective date has come, unless litigation or a court order disputes it. */
    static boolean tookEffect(RuleState state, LocalDate asOf) {
        return !state.disputed() && dateHasCome(state, asOf);
    }

    /** The final rule was scheduled to take effect by asOf, but it is disputed, so it is not shown as in effect. */
    static boolean effectiveDatePassedWhileDisputed(RuleState state, LocalDate asOf) {
        return state.disputed() && dateHasCome(state, asOf);
    }

    private static boolean dateHasCome(RuleState state, LocalDate asOf) {
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
        // Disputed when the date came: it took effect when an admin's step cleared the dispute.
        boolean waitedForClearing = false;
        while (position < steps.size() && steps.get(position - 1).disputed()) {
            position++;
            waitedForClearing = true;
        }
        Step before = steps.get(position - 1);
        if (before.disputed()) {
            // The steps end disputed although the rule is not: leave it out rather than guess when.
            return;
        }
        LocalDate tookEffectOn = waitedForClearing ? before.observedOn() : effectiveDate;
        steps.add(position, new Step(tookEffectOn, null, true, PolicyStatus.FINAL, before.primaryStage(),
                effectiveDate, before.effectiveDate(), null, before.disputed(), List.of(RuleChange.TOOK_EFFECT),
                steps.get(setter).source()));
    }
}
