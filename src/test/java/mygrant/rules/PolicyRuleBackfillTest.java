package mygrant.rules;

import static mygrant.rules.RuleFixtures.FINAL_EFFECTIVE;
import static mygrant.rules.RuleFixtures.FINAL_RULE;
import static mygrant.rules.RuleFixtures.OLD_PROPOSAL;
import static mygrant.rules.RuleFixtures.PROPOSAL;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import mygrant.ingestion.connector.ContentHash;
import mygrant.ingestion.dto.IngestPolicyRequest;
import mygrant.policies.PolicyDocument;
import mygrant.policies.PolicyDocumentRepository;
import mygrant.policies.PolicyStatus;
import mygrant.rules.PolicyRuleStore.HistoryRow;

/**
 * The startup backfill against PostgreSQL, for documents stored before policy rules existed.
 * Rolls back; the backfill joins the test transaction.
 */
@SpringBootTest
@Transactional
@org.springframework.test.context.event.RecordApplicationEvents
class PolicyRuleBackfillTest {

    @Autowired PolicyRuleBackfill backfill;
    @Autowired PolicyRuleStore store;
    @Autowired PolicyDocumentRepository policies;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @MockBean VectorStore vectors;
    @Autowired org.springframework.test.context.event.ApplicationEvents events;

    @BeforeEach
    void isolateFixtures() {
        RuleFixtures.isolate(jdbc, List.of(PROPOSAL, FINAL_RULE, OLD_PROPOSAL));
    }

    @Test
    void linksStoredDocumentsInPublicationOrderWithoutAlerting() {
        // Stored newest first, as an import of recent documents would, and without rules.
        long finalRule = stored(RuleFixtures.finalRule());
        long proposal = stored(RuleFixtures.proposal());
        long oldProposal = stored(RuleFixtures.oldProposal());

        PolicyRuleBackfill.Result result = backfill.run();

        assertThat(result.failed()).isZero();
        assertThat(result.linked()).isGreaterThanOrEqualTo(3);
        long ruleId = store.findLink(proposal).orElseThrow().ruleId();
        assertThat(store.findLink(finalRule).orElseThrow().ruleId()).isEqualTo(ruleId);
        assertThat(store.findLink(oldProposal).orElseThrow().ruleId()).isNotEqualTo(ruleId);
        assertThat(store.findRule(ruleId).orElseThrow().state())
                .isEqualTo(new RuleState(PolicyStatus.FINAL, FINAL_EFFECTIVE, false));
        List<HistoryRow> history = store.history(ruleId);
        assertThat(history).extracting(row -> row.state().primaryStage())
                .containsExactly(PolicyStatus.PROPOSED, PolicyStatus.FINAL);
        assertThat(history).allSatisfy(row -> {
            assertThat(row.applied()).isTrue();
            assertThat(row.alerted()).isFalse();
            assertThat(row.recordedBy()).isEqualTo(RecordedBy.BACKFILL);
        });
        // PROPOSED to FINAL is a meaningful change, but nobody is alerted about old documents.
        assertThat(events.stream(PolicyChangedEvent.class)).isEmpty();
        assertThat(events.stream(RuleLinkFlaggedEvent.class)).isEmpty();
    }

    @Test
    void runningItTwiceChangesNothing() {
        stored(RuleFixtures.finalRule());
        stored(RuleFixtures.proposal());
        stored(RuleFixtures.oldProposal());
        backfill.run();
        Map<String, Object> afterFirstRun = snapshot();

        PolicyRuleBackfill.Result second = backfill.run();

        assertThat(second.linked()).isZero();
        assertThat(second.failed()).isZero();
        assertThat(snapshot()).isEqualTo(afterFirstRun);
    }

    /** Every row of the four rule tables, so any change between runs shows up. */
    private Map<String, Object> snapshot() {
        return jdbc.queryForMap("""
                SELECT
                  (SELECT count(*) FROM policy_rules) AS rules,
                  (SELECT string_agg(id || ':' || primary_stage || ':' || coalesce(effective_date::text, '-')
                      || ':' || disputed || ':' || primary_as_of || ':' || updated_at, ',' ORDER BY id)
                     FROM policy_rules) AS rule_rows,
                  (SELECT string_agg(policy_id || ':' || rule_id || ':' || match_method || ':' || needs_review,
                      ',' ORDER BY policy_id) FROM policy_rule_documents) AS links,
                  (SELECT string_agg(policy_id || ':' || kind || ':' || value, ',' ORDER BY policy_id, kind, value)
                     FROM policy_document_identifiers) AS identifiers,
                  (SELECT string_agg(id || ':' || dedupe_key, ',' ORDER BY id) FROM policy_rule_history) AS history
                """, Map.of());
    }

    private long stored(IngestPolicyRequest request) {
        return policies.saveAndFlush(new PolicyDocument(request.externalId(), request.title(), request.agency(),
                request.visaType(), request.status(), request.sourceType(), request.publicationDate(),
                request.effectiveDate(), request.sourceUrl(), request.content(),
                ContentHash.sha256(request.content()))).getId();
    }
}
