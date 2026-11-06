package mygrant.rules;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
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
import mygrant.policies.PolicyDocumentRepository;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;
import mygrant.rules.PolicyRuleStore.NewHistoryEntry;
import mygrant.rules.PolicyRuleStore.Rule;

/**
 * Puts each stored document in its policy rule and records the stage history.
 *
 * <p>Linking: a document joins the rule whose primary documents share a docket, RIN or (for news)
 * a linked Federal Register document number with it. A secondary document's links are read from its
 * text, its sourceUrl and its sourceReferences, so an admin can record a court order that no
 * Federal Register document reports by posting it with a link to the rule. Any one shared identifier is enough, so a
 * document with several joins on whichever matches. When identifiers point to more than one rule
 * the document joins the one matched by docket (else RIN, else document number), with MEDIUM
 * confidence, flagged for review. When nothing matches, the title fallback ({@link TitleSimilarity})
 * may suggest a rule, but never one whose docket or RIN differs from the document's: conflicting
 * identifiers beat a similar title. A title match is LOW confidence and always flagged. A primary
 * document that matches nothing starts a rule; a secondary (news) document never does.
 *
 * <p>Changes: a meaningful change raises one {@link PolicyChangedEvent}. That is a primary document
 * moving a staged rule forward (PROPOSED to FINAL, or to WITHDRAWN) or changing its effective date,
 * or a secondary source linked by an identifier (HIGH confidence) reporting litigation or a stay,
 * which sets disputed and leaves the stage alone. Nothing is raised for a rule's first document,
 * for a document that changes nothing, for an older document arriving late, or by the backfill.
 *
 * <p>On a rule already final, only a document about the effective date ({@link DateNotice}), such as
 * a delay notice, moves the date. Any other final document is a separate final rule in the same
 * rulemaking: it is recorded as its own step with its own effective date, the rule's date stays,
 * and nothing is raised, so it gets the usual new-policy alert. A document about the date gives
 * its new date in its DATES paragraph, not in the API's effective_on. When a delay or
 * postponement notice gives none that can be read, its step is recorded, the date stays, nothing
 * is raised, and it is flagged for review with the DATES text as the reason. A correction that
 * gives none leaves the date alone without a flag.
 */
@Service
public class PolicyRuleTracker {

    static final String UNCONFIRMED_LITIGATION = "Litigation reported, but not applied until the link is confirmed.";

    private final PolicyRuleStore store;
    private final PolicyDocumentRepository policies;
    private final ApplicationEventPublisher events;

    public PolicyRuleTracker(PolicyRuleStore store, PolicyDocumentRepository policies,
            ApplicationEventPublisher events) {
        this.store = store;
        this.policies = policies;
        this.events = events;
    }

    /**
     * What tracking a document did. ruleId is null when the document joined no rule; changeAlerted
     * is true when it raised a PolicyChangedEvent, whose alert replaces the generic new-policy one.
     */
    public record Result(Long ruleId, boolean linked, boolean changeAlerted) {
        static Result unlinked() {
            return new Result(null, false, false);
        }
    }

    /**
     * What applying a primary document did: a review reason if it was not applied, or applied
     * without its new date, and any change to raise.
     */
    private record Applied(String reviewReason, Optional<PolicyChangedEvent> change) {
        static final Applied NOTHING = new Applied(null, Optional.empty());
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
            return new Result(existing.get().ruleId(), false, false);
        }
        Set<RuleIdentifier> identifiers = identifiers(document, knownIdentifiers);
        store.saveIdentifiers(document.getId(), identifiers);

        Optional<Match> match = match(document, identifiers);
        if (match.isEmpty()) {
            if (document.getSourceType() != SourceType.PRIMARY) {
                return Result.unlinked();
            }
            return new Result(startRule(document, identifiers, recordedBy), true, false);
        }
        Match found = match.get();
        store.link(document.getId(), found.ruleId(), found.method(), found.confidence(), found.matchedOn(),
                found.reviewReason());
        Rule rule = store.findRule(found.ruleId()).orElseThrow();
        String flagged = found.reviewReason();
        Optional<PolicyChangedEvent> change = Optional.empty();
        String notApplied = null;
        if (document.getSourceType() == SourceType.PRIMARY) {
            Applied applied = applyPrimary(document, rule, recordedBy);
            notApplied = applied.reviewReason();
            change = applied.change();
        } else if (LitigationSignal.reports(document.getTitle() + "\n" + document.getContent())) {
            // A title-only or ambiguous link is not certain enough to call a rule disputed.
            if (found.confidence() == Confidence.HIGH) {
                change = applyDispute(document, rule, recordedBy);
            } else {
                notApplied = UNCONFIRMED_LITIGATION;
            }
        }
        if (notApplied != null) {
            store.flagForReview(document.getId(), notApplied);
            flagged = flagged == null ? notApplied : flagged + " " + notApplied;
        }
        if (flagged != null && recordedBy == RecordedBy.INGESTION) {
            events.publishEvent(new RuleLinkFlaggedEvent(document.getId(), found.ruleId(), found.method(),
                    found.confidence(), flagged));
        }
        change.ifPresent(events::publishEvent);
        return new Result(found.ruleId(), true, change.isPresent());
    }

    /**
     * A primary document's own header. For a secondary one, dockets, RINs and links anywhere in its
     * text, and Federal Register and regulations.gov links in its sourceUrl and sourceReferences.
     */
    private static Set<RuleIdentifier> identifiers(PolicyDocument document, Set<RuleIdentifier> known) {
        Set<RuleIdentifier> identifiers = new LinkedHashSet<>(known);
        if (document.getSourceType() == SourceType.PRIMARY) {
            identifiers.addAll(RuleIdentifiers.fromHeader(document.getContent()));
        } else {
            identifiers.addAll(RuleIdentifiers.fromNewsText(document.getTitle() + "\n" + document.getContent()));
            List<String> links = new ArrayList<>(document.getSourceReferences());
            links.add(document.getSourceUrl());
            identifiers.addAll(RuleIdentifiers.fromLinks(links));
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
        RuleState state = new RuleState(stage, effectiveDate(document, stage, aboutEffectiveDate(document)), false);
        long ruleId = store.createRule(document.getTitle(), state, document.getPublicationDate());
        // Without a docket or RIN nothing ties later documents to this one, so starting a rule is a guess.
        store.link(document.getId(), ruleId, MatchMethod.NEW_RULE,
                identifiers.isEmpty() ? Confidence.MEDIUM : Confidence.HIGH, describe(identifiers), null);
        store.appendHistory(new NewHistoryEntry(ruleId, null, state, true, false, aboutEffectiveDate(document),
                RuleSource.of(document), recordedBy));
        return ruleId;
    }

    /**
     * Applies a primary document to its rule. A document published before the one behind the
     * current state is recorded in order but changes nothing. Otherwise the stage moves forward
     * only, and a document without an effective date keeps the stored one. A document that would
     * move the stage backwards is not applied and comes back with a review reason. A separate
     * final rule on a rule already final is recorded with its own effective date and changes
     * neither the stage nor the rule's date. A delay notice whose new date cannot be read is
     * recorded and flagged, and leaves the date alone.
     */
    private Applied applyPrimary(PolicyDocument document, Rule rule, RecordedBy recordedBy) {
        RuleState before = rule.state();
        PolicyStatus docStage = RuleStages.of(document.getStatus());
        boolean aboutDate = aboutEffectiveDate(document);
        LocalDate docDate = effectiveDate(document, docStage, aboutDate);
        RuleSource source = RuleSource.of(document);
        // A delay notice must give a new date, so one that cannot be read is flagged. A correction
        // that names none fixed something else and changes nothing.
        String unreadDate = docStage == PolicyStatus.FINAL && aboutDate && docDate == null
                && DateNotice.announcesNewDate(document.getTitle(), document.getContent())
                ? unreadDateReason(document) : null;

        if (document.getPublicationDate().isBefore(rule.primaryAsOf())) {
            if (RuleStages.isStage(docStage) || docDate != null) {
                store.appendHistory(new NewHistoryEntry(rule.id(), before, new RuleState(docStage, docDate, false),
                        false, false, aboutDate, source, recordedBy));
            }
            return new Applied(unreadDate, Optional.empty());
        }
        if (RuleStages.isBackward(before.primaryStage(), docStage)) {
            return new Applied("Stage not changed: a " + lower(docStage) + " document published after the rule became "
                    + lower(before.primaryStage()) + ".", Optional.empty());
        }
        if (RuleStages.isAdditionalFinalRule(before.primaryStage(), docStage, aboutDate)) {
            // Its row holds its own effective date; the rule keeps its own. It is applied, so it
            // becomes the newest primary document, and an older one arriving later is late.
            store.updateRule(rule.id(), before, document.getPublicationDate());
            store.appendHistory(new NewHistoryEntry(rule.id(), before,
                    new RuleState(PolicyStatus.FINAL, docDate, before.disputed()), true, false, aboutDate,
                    source, recordedBy));
            return Applied.NOTHING;
        }
        RuleState after = new RuleState(RuleStages.next(before.primaryStage(), docStage),
                docDate != null ? docDate : before.effectiveDate(), before.disputed());
        store.updateRule(rule.id(), after, document.getPublicationDate());
        if (after.equals(before)) {
            if (unreadDate != null) {
                // A delay notice whose new date could not be read: its step is kept for the reviewer.
                store.appendHistory(new NewHistoryEntry(rule.id(), before, after, true, false, aboutDate, source,
                        recordedBy));
            }
            return new Applied(unreadDate, Optional.empty());
        }
        // A rule started by a notice that gets its first real stage is new, not changed: the
        // generic new-policy alert covers it.
        boolean alerted = recordedBy == RecordedBy.INGESTION && RuleStages.isStage(before.primaryStage());
        Optional<Long> historyId = store.appendHistory(
                new NewHistoryEntry(rule.id(), before, after, true, alerted, aboutDate, source, recordedBy));
        if (!alerted || historyId.isEmpty()) {
            return new Applied(unreadDate, Optional.empty());
        }
        return new Applied(unreadDate, Optional.of(event(rule, historyId.get(), before, after, document, document)));
    }

    /**
     * Marks the rule disputed because a linked secondary source, news or an admin-recorded court
     * order, reports litigation or a stay. The primary stage and effective date stay as the primary
     * sources set them. A rule already disputed is a repeat.
     */
    private Optional<PolicyChangedEvent> applyDispute(PolicyDocument report, Rule rule, RecordedBy recordedBy) {
        RuleState before = rule.state();
        if (before.disputed()) {
            return Optional.empty();
        }
        RuleState after = new RuleState(before.primaryStage(), before.effectiveDate(), true);
        store.updateRule(rule.id(), after, rule.primaryAsOf());
        boolean alerted = recordedBy == RecordedBy.INGESTION;
        Optional<Long> historyId = store.appendHistory(
                new NewHistoryEntry(rule.id(), before, after, true, alerted, false, RuleSource.of(report), recordedBy));
        if (!alerted || historyId.isEmpty()) {
            return Optional.empty();
        }
        // Who is alerted, and what the impact rules read, come from the rule's own primary document.
        PolicyDocument primary = store.currentPrimaryDocumentId(rule.id()).flatMap(policies::findById)
                .orElseThrow(() -> new IllegalStateException("Rule " + rule.id() + " has no primary document"));
        return Optional.of(event(rule, historyId.get(), before, after, report, primary));
    }

    private static PolicyChangedEvent event(Rule rule, long historyId, RuleState before, RuleState after,
            PolicyDocument source, PolicyDocument primary) {
        List<String> visaTypes = Arrays.stream(primary.getVisaType().split(","))
                .map(String::trim).filter(type -> !type.isEmpty()).toList();
        return new PolicyChangedEvent(rule.id(), historyId, rule.title(), before, after,
                RuleChange.between(before, after), RuleSource.of(source), visaTypes, primary.getTitle(),
                primary.getContent());
    }

    /** Whether a primary document is about the effective date: a delay, postponement or correction. */
    private static boolean aboutEffectiveDate(PolicyDocument document) {
        return DateNotice.concernsEffectiveDate(document.getTitle(), document.getContent());
    }

    /**
     * Only a final rule's effective date is the rule's; a proposal or notice can carry its own. A
     * final document about the date gives its new date in DATES: the API's effective_on is not it.
     * Null when that cannot be read.
     */
    private static LocalDate effectiveDate(PolicyDocument document, PolicyStatus stage, boolean aboutDate) {
        if (stage != PolicyStatus.FINAL) {
            return null;
        }
        return aboutDate ? DateNotice.newEffectiveDate(document.getContent()).orElse(null) : document.getEffectiveDate();
    }

    private static String unreadDateReason(PolicyDocument document) {
        return "Effective date not changed: this document is about the effective date, but no new date could be "
                + "read from its DATES paragraph. DATES: "
                + DateNotice.dates(document.getContent()).orElse("(no DATES paragraph found)");
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
