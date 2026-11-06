package mygrant.rules;

import static mygrant.rules.RuleFixtures.FINAL_EFFECTIVE;
import static mygrant.rules.RuleFixtures.FINAL_PUBLISHED;
import static mygrant.rules.RuleFixtures.FINAL_RULE;
import static mygrant.rules.RuleFixtures.PROPOSAL;
import static mygrant.rules.RuleFixtures.PROPOSAL_PUBLISHED;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import mygrant.common.Confidence;
import mygrant.ingestion.PolicyIngestionService;
import mygrant.ingestion.dto.IngestPolicyRequest;
import mygrant.policies.PolicyDocumentRepository;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;
import mygrant.rules.PolicyRuleStore.HistoryRow;

/**
 * CS203-133 against PostgreSQL: rules, their identifiers and their sourced history, written by
 * ingestion. Every test rolls back.
 */
@SpringBootTest
@Transactional
class PolicyRuleTrackingIntegrationTest {

    @Autowired PolicyIngestionService ingestion;
    @Autowired PolicyRuleTracker tracker;
    @Autowired PolicyRuleStore store;
    @Autowired PolicyDocumentRepository policies;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @MockBean VectorStore vectors;

    @BeforeEach
    void isolateFixtures() {
        RuleFixtures.isolate(jdbc, List.of(PROPOSAL, FINAL_RULE, "2026-99901"));
    }

    @Test
    void theProposalAndFinalRuleShareOneRuleWithSourcedHistory() {
        long proposal = ingest(RuleFixtures.proposal());
        long finalRule = ingest(RuleFixtures.finalRule());

        var proposalLink = store.findLink(proposal).orElseThrow();
        var finalLink = store.findLink(finalRule).orElseThrow();
        assertThat(finalLink.ruleId()).isEqualTo(proposalLink.ruleId());
        assertThat(proposalLink.method()).isEqualTo(MatchMethod.NEW_RULE);
        assertThat(finalLink.method()).isEqualTo(MatchMethod.DOCKET_AND_RIN);
        assertThat(finalLink.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(finalLink.matchedOn()).isEqualTo("DOCKET ICEB-2025-0001; RIN 1653-AA95");
        assertThat(finalLink.needsReview()).isFalse();
        assertThat(store.identifiersOf(finalRule))
                .containsExactly(RuleIdentifier.docket("ICEB-2025-0001"), RuleIdentifier.rin("1653-AA95"));

        var rule = store.findRule(finalLink.ruleId()).orElseThrow();
        assertThat(rule.state()).isEqualTo(new RuleState(PolicyStatus.FINAL, FINAL_EFFECTIVE, false));
        assertThat(rule.primaryAsOf()).isEqualTo(FINAL_PUBLISHED);

        List<HistoryRow> history = store.history(rule.id());
        assertThat(history).hasSize(2);
        HistoryRow proposed = history.get(0);
        assertThat(proposed.state()).isEqualTo(new RuleState(PolicyStatus.PROPOSED, null, false));
        assertThat(proposed.previous()).isNull();
        assertThat(proposed.source()).isEqualTo(new RuleSource(proposal, PROPOSAL, SourceType.PRIMARY,
                "https://www.federalregister.gov/d/" + PROPOSAL, RuleFixtures.TITLE, "Homeland Security Department",
                PROPOSAL_PUBLISHED));
        HistoryRow finalized = history.get(1);
        assertThat(finalized.state()).isEqualTo(new RuleState(PolicyStatus.FINAL, FINAL_EFFECTIVE, false));
        assertThat(finalized.previous()).isEqualTo(new RuleState(PolicyStatus.PROPOSED, null, false));
        assertThat(finalized.source().externalId()).isEqualTo(FINAL_RULE);
        assertThat(finalized.applied()).isTrue();
        assertThat(history).extracting(HistoryRow::recordedBy).containsOnly(RecordedBy.INGESTION);
    }

    @Test
    void importingEitherDocumentAgainAddsNoHistory() {
        long proposal = ingest(RuleFixtures.proposal());
        long finalRule = ingest(RuleFixtures.finalRule());
        long ruleId = store.findLink(finalRule).orElseThrow().ruleId();
        List<HistoryRow> before = store.history(ruleId);

        assertThat(ingestion.ingest(RuleFixtures.proposal()).alreadyIndexed()).isTrue();
        assertThat(ingestion.ingest(RuleFixtures.finalRule()).alreadyIndexed()).isTrue();
        // Tracking a document already in a rule is a no-op too.
        var again = tracker.track(policies.findById(proposal).orElseThrow(), Set.of(), RecordedBy.INGESTION);

        assertThat(again).isEqualTo(new PolicyRuleTracker.Result(ruleId, false));
        assertThat(store.history(ruleId)).isEqualTo(before);
    }

    @Test
    void theSameChangeFromTheSameSourceIsWrittenOnce() {
        long finalRule = ingest(RuleFixtures.finalRule());
        long ruleId = store.findLink(finalRule).orElseThrow().ruleId();
        var entry = new PolicyRuleStore.NewHistoryEntry(ruleId, new RuleState(PolicyStatus.PROPOSED, null, false),
                new RuleState(PolicyStatus.FINAL, FINAL_EFFECTIVE, false), true, false,
                RuleSource.of(policies.findById(finalRule).orElseThrow()), RecordedBy.INGESTION);

        assertThat(store.appendHistory(entry)).isPresent();
        assertThat(store.appendHistory(entry)).isEmpty();
    }

    @Test
    void anOlderDocumentArrivingLaterIsRecordedButDoesNotMoveTheRule() {
        long finalRule = ingest(RuleFixtures.finalRule());
        long proposal = ingest(RuleFixtures.proposal());
        long ruleId = store.findLink(finalRule).orElseThrow().ruleId();

        assertThat(store.findLink(proposal).orElseThrow().ruleId()).isEqualTo(ruleId);
        assertThat(store.findRule(ruleId).orElseThrow().state())
                .isEqualTo(new RuleState(PolicyStatus.FINAL, FINAL_EFFECTIVE, false));
        List<HistoryRow> history = store.history(ruleId);
        assertThat(history).extracting(row -> row.source().externalId()).containsExactly(PROPOSAL, FINAL_RULE);
        HistoryRow late = history.get(0);
        assertThat(late.applied()).isFalse();
        assertThat(late.state()).isEqualTo(new RuleState(PolicyStatus.PROPOSED, null, false));
        // The stored previous value is what the rule held when the row was written: an audit record.
        assertThat(late.previous()).isEqualTo(new RuleState(PolicyStatus.FINAL, FINAL_EFFECTIVE, false));
    }

    @Test
    void aDocumentWithoutAnEffectiveDateNeverClearsTheStoredOne() {
        long finalRule = ingest(RuleFixtures.finalRule());
        long ruleId = store.findLink(finalRule).orElseThrow().ruleId();
        // A later final-rule document in the docket (a correction, say) that carries no effective date.
        ingest(new IngestPolicyRequest("2026-99901", RuleFixtures.TITLE + "; Correction", "Homeland Security Department",
                "F-1,J-1", PolicyStatus.FINAL, SourceType.PRIMARY, LocalDate.of(2026, 8, 3), null,
                "https://www.federalregister.gov/d/2026-99901",
                "[DHS Docket No. ICEB-2025-0001]\nRIN 1653-AA95\n\nSUMMARY: Corrects a citation."));

        var rule = store.findRule(ruleId).orElseThrow();
        assertThat(rule.state().effectiveDate()).isEqualTo(FINAL_EFFECTIVE);
        assertThat(rule.primaryAsOf()).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(store.history(ruleId)).hasSize(1);
    }

    @Test
    void aStageNeverMovesBackwards() {
        long finalRule = ingest(RuleFixtures.finalRule());
        long ruleId = store.findLink(finalRule).orElseThrow().ruleId();
        // A proposal in the same docket published after the final rule.
        ingest(new IngestPolicyRequest("2026-99901", RuleFixtures.TITLE, "Homeland Security Department", "F-1,J-1",
                PolicyStatus.PROPOSED, SourceType.PRIMARY, LocalDate.of(2026, 10, 1), null,
                "https://www.federalregister.gov/d/2026-99901",
                "[DHS Docket No. ICEB-2025-0001]\nRIN 1653-AA95\n\nSUMMARY: Proposes amendments."));

        assertThat(store.findRule(ruleId).orElseThrow().state().primaryStage()).isEqualTo(PolicyStatus.FINAL);
        assertThat(store.history(ruleId)).hasSize(1);
    }

    private long ingest(IngestPolicyRequest request) {
        return ingestion.ingest(request).policyId();
    }
}
