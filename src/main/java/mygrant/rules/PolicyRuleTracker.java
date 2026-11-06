package mygrant.rules;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mygrant.common.Confidence;
import mygrant.policies.PolicyDocument;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;
import mygrant.rules.PolicyRuleStore.NewHistoryEntry;
import mygrant.rules.PolicyRuleStore.Rule;

/**
 * Puts each stored document in its policy rule and records the stage history. A primary document
 * that shares a docket or RIN with a rule joins it; otherwise it starts a new rule. Secondary
 * (news) documents only ever join a rule.
 */
@Service
public class PolicyRuleTracker {

    private final PolicyRuleStore store;

    public PolicyRuleTracker(PolicyRuleStore store) {
        this.store = store;
    }

    /** What tracking a document did. ruleId is null when the document joined no rule. */
    public record Result(Long ruleId, boolean linked) {
        static Result unlinked() {
            return new Result(null, false);
        }
    }

    /**
     * Links a newly stored document and records what it changed. A document already in a rule is
     * left alone, so tracking it again changes nothing. Runs in the caller's transaction, which
     * must be the one that stored the document.
     */
    @Transactional
    public Result track(PolicyDocument document, Set<RuleIdentifier> knownIdentifiers, RecordedBy recordedBy) {
        store.lockLinking();
        var existing = store.findLink(document.getId());
        if (existing.isPresent()) {
            return new Result(existing.get().ruleId(), false);
        }
        Set<RuleIdentifier> identifiers = new LinkedHashSet<>(knownIdentifiers);
        identifiers.addAll(RuleIdentifiers.fromHeader(document.getContent()));
        store.saveIdentifiers(document.getId(), identifiers);

        Map<Long, Set<RuleIdentifier>> candidates = store.rulesMatching(identifiers);
        if (candidates.isEmpty()) {
            if (document.getSourceType() != SourceType.PRIMARY) {
                return Result.unlinked();
            }
            return new Result(startRule(document, identifiers, recordedBy), true);
        }
        long ruleId = candidates.keySet().iterator().next();
        Set<RuleIdentifier> matched = candidates.get(ruleId);
        store.link(document.getId(), ruleId, method(matched), Confidence.HIGH, describe(matched), null);
        if (document.getSourceType() == SourceType.PRIMARY) {
            applyPrimary(document, store.findRule(ruleId).orElseThrow(), recordedBy);
        }
        return new Result(ruleId, true);
    }

    private long startRule(PolicyDocument document, Set<RuleIdentifier> identifiers, RecordedBy recordedBy) {
        PolicyStatus stage = RuleStages.of(document.getStatus());
        RuleState state = new RuleState(stage, effectiveDate(document, stage), false);
        long ruleId = store.createRule(document.getTitle(), state, document.getPublicationDate());
        // Without a docket or RIN nothing ties later documents to this one, so starting a rule is a guess.
        store.link(document.getId(), ruleId, MatchMethod.NEW_RULE,
                identifiers.isEmpty() ? Confidence.MEDIUM : Confidence.HIGH, describe(identifiers), null);
        store.appendHistory(new NewHistoryEntry(ruleId, null, state, true, false, RuleSource.of(document), recordedBy));
        return ruleId;
    }

    /**
     * Applies a primary document to its rule. A document published before the one behind the
     * current state is recorded in order but changes nothing. Otherwise the stage moves forward
     * only, and a document without an effective date keeps the stored one.
     */
    private Optional<Long> applyPrimary(PolicyDocument document, Rule rule, RecordedBy recordedBy) {
        RuleState before = rule.state();
        PolicyStatus docStage = RuleStages.of(document.getStatus());
        LocalDate docDate = effectiveDate(document, docStage);
        RuleSource source = RuleSource.of(document);

        if (document.getPublicationDate().isBefore(rule.primaryAsOf())) {
            if (RuleStages.isStage(docStage) || docDate != null) {
                store.appendHistory(new NewHistoryEntry(rule.id(), before, new RuleState(docStage, docDate, false),
                        false, false, source, recordedBy));
            }
            return Optional.empty();
        }
        if (RuleStages.isBackward(before.primaryStage(), docStage)) {
            return Optional.empty();
        }
        RuleState after = new RuleState(RuleStages.next(before.primaryStage(), docStage),
                docDate != null ? docDate : before.effectiveDate(), before.disputed());
        store.updateRule(rule.id(), after, document.getPublicationDate());
        if (after.equals(before)) {
            return Optional.empty();
        }
        return store.appendHistory(new NewHistoryEntry(rule.id(), before, after, true, false, source, recordedBy));
    }

    /** Only a final rule's effective date is the rule's; a proposal or notice can carry its own. */
    private static LocalDate effectiveDate(PolicyDocument document, PolicyStatus stage) {
        return stage == PolicyStatus.FINAL ? document.getEffectiveDate() : null;
    }

    private static MatchMethod method(Set<RuleIdentifier> matched) {
        Set<IdentifierKind> kinds = matched.stream().map(RuleIdentifier::kind)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(IdentifierKind.class)));
        if (kinds.contains(IdentifierKind.DOCKET) && kinds.contains(IdentifierKind.RIN)) {
            return MatchMethod.DOCKET_AND_RIN;
        }
        if (kinds.contains(IdentifierKind.DOCKET)) {
            return MatchMethod.DOCKET;
        }
        return kinds.contains(IdentifierKind.RIN) ? MatchMethod.RIN : MatchMethod.FR_DOCUMENT;
    }

    private static String describe(Set<RuleIdentifier> identifiers) {
        return identifiers.isEmpty() ? null
                : identifiers.stream().map(RuleIdentifier::toString).collect(Collectors.joining("; "));
    }
}
