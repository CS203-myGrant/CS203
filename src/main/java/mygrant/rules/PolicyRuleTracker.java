package mygrant.rules;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mygrant.common.Confidence;
import mygrant.policies.PolicyDocument;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;
import mygrant.rules.PolicyRuleStore.NewHistoryEntry;
import mygrant.rules.PolicyRuleStore.Rule;

/**
 * Puts each stored document in its policy rule and records the stage history.
 *
 * <p>Linking: a document joins the rule whose primary documents share a docket, RIN or (for news)
 * a linked Federal Register document number with it. Any one shared identifier is enough, so a
 * document with several joins on whichever matches. When identifiers point to more than one rule
 * the document joins the one matched by docket (else RIN, else document number), with MEDIUM
 * confidence, flagged for review. When nothing matches, the title fallback ({@link TitleSimilarity})
 * may suggest a rule, but never one whose docket or RIN differs from the document's: conflicting
 * identifiers beat a similar title. A title match is LOW confidence and always flagged. A primary
 * document that matches nothing starts a rule; a secondary (news) document never does.
 */
@Service
public class PolicyRuleTracker {

    private final PolicyRuleStore store;
    private final ApplicationEventPublisher events;

    public PolicyRuleTracker(PolicyRuleStore store, ApplicationEventPublisher events) {
        this.store = store;
        this.events = events;
    }

    /** What tracking a document did. ruleId is null when the document joined no rule. */
    public record Result(Long ruleId, boolean linked) {
        static Result unlinked() {
            return new Result(null, false);
        }
    }

    private record Match(long ruleId, MatchMethod method, Confidence confidence, String matchedOn,
            String reviewReason) {
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
        Set<RuleIdentifier> identifiers = identifiers(document, knownIdentifiers);
        store.saveIdentifiers(document.getId(), identifiers);

        Optional<Match> match = match(document, identifiers);
        if (match.isEmpty()) {
            if (document.getSourceType() != SourceType.PRIMARY) {
                return Result.unlinked();
            }
            return new Result(startRule(document, identifiers, recordedBy), true);
        }
        Match found = match.get();
        store.link(document.getId(), found.ruleId(), found.method(), found.confidence(), found.matchedOn(),
                found.reviewReason());
        String flagged = found.reviewReason();
        if (document.getSourceType() == SourceType.PRIMARY) {
            String backward = applyPrimary(document, store.findRule(found.ruleId()).orElseThrow(), recordedBy);
            if (backward != null) {
                store.flagForReview(document.getId(), backward);
                flagged = flagged == null ? backward : flagged + " " + backward;
            }
        }
        if (flagged != null && recordedBy == RecordedBy.INGESTION) {
            events.publishEvent(new RuleLinkFlaggedEvent(document.getId(), found.ruleId(), found.method(),
                    found.confidence(), flagged));
        }
        return new Result(found.ruleId(), true);
    }

    /** A primary document's own header; for news, dockets, RINs and links anywhere in its text. */
    private static Set<RuleIdentifier> identifiers(PolicyDocument document, Set<RuleIdentifier> known) {
        Set<RuleIdentifier> identifiers = new LinkedHashSet<>(known);
        if (document.getSourceType() == SourceType.PRIMARY) {
            identifiers.addAll(RuleIdentifiers.fromHeader(document.getContent()));
        } else {
            identifiers.addAll(RuleIdentifiers.fromNewsText(document.getTitle() + "\n" + document.getContent()));
        }
        return identifiers;
    }

    private Optional<Match> match(PolicyDocument document, Set<RuleIdentifier> identifiers) {
        Map<Long, Set<RuleIdentifier>> byIdentifier = store.rulesMatching(identifiers);
        if (byIdentifier.isEmpty()) {
            return titleMatch(document, identifiers);
        }
        List<Long> ranked = byIdentifier.keySet().stream()
                .sorted(Comparator.<Long>comparingInt(id -> strength(byIdentifier.get(id))).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
        long chosen = ranked.getFirst();
        Set<RuleIdentifier> matched = byIdentifier.get(chosen);
        if (ranked.size() == 1) {
            return Optional.of(new Match(chosen, method(matched), Confidence.HIGH, describe(matched), null));
        }
        String others = ranked.stream().skip(1)
                .map(id -> "rule " + id + " (" + describe(byIdentifier.get(id)) + ")")
                .collect(Collectors.joining(", "));
        return Optional.of(new Match(chosen, method(matched), Confidence.MEDIUM, describe(matched),
                "Identifiers point to more than one rule; also " + others + "."));
    }

    /** Docket beats RIN beats document number; then more identifiers beat fewer. */
    private static int strength(Set<RuleIdentifier> matched) {
        Set<IdentifierKind> kinds = kinds(matched);
        int rank = kinds.contains(IdentifierKind.DOCKET) ? 3 : kinds.contains(IdentifierKind.RIN) ? 2 : 1;
        return rank * 100 + matched.size();
    }

    private Optional<Match> titleMatch(PolicyDocument document, Set<RuleIdentifier> identifiers) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        for (var candidate : store.primaryTitles()) {
            double score = TitleSimilarity.similarity(document.getTitle(), candidate.title());
            if (score >= TitleSimilarity.THRESHOLD) {
                scores.merge(candidate.ruleId(), score, Math::max);
            }
        }
        if (scores.isEmpty()) {
            return Optional.empty();
        }
        // A rule with a docket or RIN of the same kind as the document's, none of them shared,
        // is a different rulemaking with a similar title, like the 2020 and 2025 proposals.
        Set<IdentifierKind> documentKinds = kinds(identifiers);
        store.primaryIdentifiers(scores.keySet()).forEach((ruleId, ruleIdentifiers) -> {
            if (kinds(ruleIdentifiers).stream().anyMatch(kind -> kind != IdentifierKind.FR_DOCUMENT
                    && documentKinds.contains(kind))) {
                scores.remove(ruleId);
            }
        });
        if (scores.isEmpty()) {
            return Optional.empty();
        }
        List<Map.Entry<Long, Double>> ranked = scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.<Long, Double>comparingByKey().reversed()))
                .toList();
        var best = ranked.getFirst();
        String reason = "Title-only match (similarity " + score(best.getValue())
                + "); no docket, RIN or document number in common.";
        if (ranked.size() > 1) {
            reason += " Other rules with a similar title: " + ranked.stream().skip(1)
                    .map(entry -> "rule " + entry.getKey() + " (" + score(entry.getValue()) + ")")
                    .collect(Collectors.joining(", ")) + ".";
        }
        return Optional.of(new Match(best.getKey(), MatchMethod.TITLE, Confidence.LOW,
                "TITLE similarity " + score(best.getValue()), reason));
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
     * only, and a document without an effective date keeps the stored one. Returns a review
     * reason when the document would have moved the stage backwards, else null.
     */
    private String applyPrimary(PolicyDocument document, Rule rule, RecordedBy recordedBy) {
        RuleState before = rule.state();
        PolicyStatus docStage = RuleStages.of(document.getStatus());
        LocalDate docDate = effectiveDate(document, docStage);
        RuleSource source = RuleSource.of(document);

        if (document.getPublicationDate().isBefore(rule.primaryAsOf())) {
            if (RuleStages.isStage(docStage) || docDate != null) {
                store.appendHistory(new NewHistoryEntry(rule.id(), before, new RuleState(docStage, docDate, false),
                        false, false, source, recordedBy));
            }
            return null;
        }
        if (RuleStages.isBackward(before.primaryStage(), docStage)) {
            return "Stage not changed: a " + lower(docStage) + " document published after the rule became "
                    + lower(before.primaryStage()) + ".";
        }
        RuleState after = new RuleState(RuleStages.next(before.primaryStage(), docStage),
                docDate != null ? docDate : before.effectiveDate(), before.disputed());
        store.updateRule(rule.id(), after, document.getPublicationDate());
        if (!after.equals(before)) {
            store.appendHistory(new NewHistoryEntry(rule.id(), before, after, true, false, source, recordedBy));
        }
        return null;
    }

    /** Only a final rule's effective date is the rule's; a proposal or notice can carry its own. */
    private static LocalDate effectiveDate(PolicyDocument document, PolicyStatus stage) {
        return stage == PolicyStatus.FINAL ? document.getEffectiveDate() : null;
    }

    private static MatchMethod method(Set<RuleIdentifier> matched) {
        Set<IdentifierKind> kinds = kinds(matched);
        if (kinds.contains(IdentifierKind.DOCKET) && kinds.contains(IdentifierKind.RIN)) {
            return MatchMethod.DOCKET_AND_RIN;
        }
        if (kinds.contains(IdentifierKind.DOCKET)) {
            return MatchMethod.DOCKET;
        }
        return kinds.contains(IdentifierKind.RIN) ? MatchMethod.RIN : MatchMethod.FR_DOCUMENT;
    }

    private static Set<IdentifierKind> kinds(Set<RuleIdentifier> identifiers) {
        return identifiers.stream().map(RuleIdentifier::kind)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(IdentifierKind.class)));
    }

    private static String describe(Set<RuleIdentifier> identifiers) {
        return identifiers.isEmpty() ? null
                : identifiers.stream().map(RuleIdentifier::toString).collect(Collectors.joining("; "));
    }

    private static String score(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String lower(PolicyStatus stage) {
        return stage.name().toLowerCase(Locale.ROOT);
    }
}
