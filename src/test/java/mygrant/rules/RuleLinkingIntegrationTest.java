package mygrant.rules;

import static mygrant.rules.RuleFixtures.FINAL_RULE;
import static mygrant.rules.RuleFixtures.OLD_PROPOSAL;
import static mygrant.rules.RuleFixtures.PROPOSAL;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import mygrant.common.Confidence;
import mygrant.ingestion.PolicyIngestionService;
import mygrant.ingestion.dto.IngestPolicyRequest;
import mygrant.ingestion.newsrss.NewsRssConnector;
import mygrant.policies.PolicyDocumentRepository;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;

/** CS203-134 against PostgreSQL: which rule a document joins, and when the link is flagged. Rolls back. */
@SpringBootTest
@Transactional
@RecordApplicationEvents
class RuleLinkingIntegrationTest {

    private static final List<String> SYNTHETIC = List.of("2026-99901", "2026-99902", "2026-99903");

    @Autowired PolicyIngestionService ingestion;
    @Autowired NewsRssConnector news;
    @Autowired PolicyRuleStore store;
    @Autowired PolicyDocumentRepository policies;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired ApplicationEvents events;
    @MockBean VectorStore vectors;

    @BeforeEach
    void isolateFixtures() {
        RuleFixtures.isolate(jdbc, List.of(PROPOSAL, FINAL_RULE, OLD_PROPOSAL, SYNTHETIC.get(0), SYNTHETIC.get(1),
                SYNTHETIC.get(2)));
        jdbc.update("DELETE FROM policy_documents WHERE external_id LIKE 'rss:guardian:%' AND title IN (:titles)",
                java.util.Map.of("titles", List.of("Judge blocks rule ending open-ended stays for F-1 students",
                        "What the new admission rule means for F-1 students",
                        "Universities sue over F-1 student admission limits")));
    }

    @Test
    void theSameTitledTwentyTwentyProposalStartsItsOwnRule() {
        long finalRule = ingest(RuleFixtures.finalRule());
        long oldProposal = ingest(RuleFixtures.oldProposal());

        var link = store.findLink(oldProposal).orElseThrow();
        assertThat(link.ruleId()).isNotEqualTo(ruleOf(finalRule));
        assertThat(link.method()).isEqualTo(MatchMethod.NEW_RULE);
        assertThat(link.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(link.needsReview()).isFalse();
        assertThat(events.stream(RuleLinkFlaggedEvent.class)).isEmpty();
    }

    @Test
    void aDocumentWithSeveralIdentifiersJoinsOnAnyOneOfThem() {
        long finalRule = ingest(RuleFixtures.finalRule());
        long joint = ingest(primary(SYNTHETIC.get(0), "Joint DHS and DOL update to student training rules",
                "[DOL Docket No. ETA-2026-0007; DHS Docket No. ICEB-2025-0001]\nRINs 1205-AC99, 1653-AB01\n\nSUMMARY: x"));

        var link = store.findLink(joint).orElseThrow();
        assertThat(link.ruleId()).isEqualTo(ruleOf(finalRule));
        assertThat(link.method()).isEqualTo(MatchMethod.DOCKET);
        assertThat(link.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(link.matchedOn()).isEqualTo("DOCKET ICEB-2025-0001");
        assertThat(store.identifiersOf(joint)).containsExactlyInAnyOrder(RuleIdentifier.docket("ETA-2026-0007"),
                RuleIdentifier.docket("ICEB-2025-0001"), RuleIdentifier.rin("1205-AC99"), RuleIdentifier.rin("1653-AB01"));
    }

    @Test
    void anUnrelatedDocumentStartsItsOwnRule() {
        long finalRule = ingest(RuleFixtures.finalRule());
        long unrelated = ingest(primary(SYNTHETIC.get(0), "Fee for Certain H-1B Petitions",
                "[DHS Docket No. USCIS-2026-0003]\nRIN 1615-AD01\n\nSUMMARY: x"));

        assertThat(ruleOf(unrelated)).isNotEqualTo(ruleOf(finalRule));
        assertThat(store.findLink(unrelated).orElseThrow().method()).isEqualTo(MatchMethod.NEW_RULE);
    }

    @Test
    void identifiersThatPointToTwoRulesAreFlaggedForReview() {
        long finalRule = ingest(RuleFixtures.finalRule());
        long oldProposal = ingest(RuleFixtures.oldProposal());
        // The 2020 docket with the 2025 RIN: a typo, or a merged rulemaking. Either way, ask.
        long mixed = ingest(primary(SYNTHETIC.get(0), "Student admission notice",
                "[DHS Docket No. ICEB-2019-0006]\nRIN 1653-AA95\n\nSUMMARY: x"));

        var link = store.findLink(mixed).orElseThrow();
        assertThat(link.ruleId()).isEqualTo(ruleOf(oldProposal));
        assertThat(link.method()).isEqualTo(MatchMethod.DOCKET);
        assertThat(link.confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(link.needsReview()).isTrue();
        assertThat(link.reviewReason()).isEqualTo("Identifiers point to more than one rule; also rule "
                + ruleOf(finalRule) + " (RIN 1653-AA95).");
        assertThat(events.stream(RuleLinkFlaggedEvent.class)).singleElement()
                .isEqualTo(new RuleLinkFlaggedEvent(mixed, ruleOf(oldProposal), MatchMethod.DOCKET, Confidence.MEDIUM,
                        link.reviewReason()));
    }

    @Test
    void aTitleOnlyMatchIsFlaggedForReview() {
        long finalRule = ingest(RuleFixtures.finalRule());
        // Entered by hand, without the Federal Register header, so it has no docket or RIN.
        long untagged = ingest(primary(SYNTHETIC.get(0), RuleFixtures.TITLE + "; Correction",
                "DHS corrects a cross-reference in the fixed admission period rule."));

        var link = store.findLink(untagged).orElseThrow();
        assertThat(link.ruleId()).isEqualTo(ruleOf(finalRule));
        assertThat(link.method()).isEqualTo(MatchMethod.TITLE);
        assertThat(link.confidence()).isEqualTo(Confidence.LOW);
        assertThat(link.matchedOn()).isEqualTo("TITLE similarity 1.00");
        assertThat(link.needsReview()).isTrue();
        assertThat(link.reviewReason())
                .isEqualTo("Title-only match (similarity 1.00); no docket, RIN or document number in common.");
        assertThat(events.stream(RuleLinkFlaggedEvent.class)).singleElement()
                .extracting(RuleLinkFlaggedEvent::method).isEqualTo(MatchMethod.TITLE);
    }

    @Test
    void aTitleMatchingSeveralRulesNamesTheOthers() {
        long finalRule = ingest(RuleFixtures.finalRule());
        long oldProposal = ingest(RuleFixtures.oldProposal());
        long untagged = ingest(primary(SYNTHETIC.get(0), RuleFixtures.TITLE, "No header."));

        var link = store.findLink(untagged).orElseThrow();
        // Equal scores: the newer rule wins and the other is named for the reviewer.
        long newer = Math.max(ruleOf(finalRule), ruleOf(oldProposal));
        long older = Math.min(ruleOf(finalRule), ruleOf(oldProposal));
        assertThat(link.ruleId()).isEqualTo(newer);
        assertThat(link.reviewReason()).endsWith("Other rules with a similar title: rule " + older + " (1.00).");
    }

    @Test
    void aStageThatWouldMoveBackwardsIsFlagged() {
        long finalRule = ingest(RuleFixtures.finalRule());
        long lateProposal = ingest(new IngestPolicyRequest(SYNTHETIC.get(0), RuleFixtures.TITLE,
                "Homeland Security Department", "F-1,J-1", PolicyStatus.PROPOSED, SourceType.PRIMARY,
                LocalDate.of(2026, 10, 1), null, "https://www.federalregister.gov/d/" + SYNTHETIC.get(0),
                "[DHS Docket No. ICEB-2025-0001]\nRIN 1653-AA95\n\nSUMMARY: Proposes amendments."));

        var link = store.findLink(lateProposal).orElseThrow();
        assertThat(link.ruleId()).isEqualTo(ruleOf(finalRule));
        assertThat(link.needsReview()).isTrue();
        assertThat(link.reviewReason())
                .isEqualTo("Stage not changed: a proposed document published after the rule became final.");
        assertThat(store.findRule(ruleOf(finalRule)).orElseThrow().state().primaryStage()).isEqualTo(PolicyStatus.FINAL);
    }

    @Test
    void newsJoinsARuleThroughItsFederalRegisterAndRegulationsGovLinks() {
        long finalRule = ingest(RuleFixtures.finalRule());

        news.importSaved(RuleFixtures.text("newsrss/synthetic-rule-coverage-feed.xml"));

        var injunction = store.findLink(newsId("Judge blocks rule ending open-ended stays for F-1 students")).orElseThrow();
        assertThat(injunction.ruleId()).isEqualTo(ruleOf(finalRule));
        assertThat(injunction.method()).isEqualTo(MatchMethod.DOCKET);
        assertThat(injunction.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(injunction.matchedOn()).isEqualTo("DOCKET ICEB-2025-0001; FR_DOCUMENT 2026-14439");
        var explainer = store.findLink(newsId("What the new admission rule means for F-1 students")).orElseThrow();
        assertThat(explainer.method()).isEqualTo(MatchMethod.FR_DOCUMENT);
        assertThat(explainer.confidence()).isEqualTo(Confidence.HIGH);
        // No link and a headline far from the rule's title: news never starts a rule.
        assertThat(store.findLink(newsId("Universities sue over F-1 student admission limits"))).isEmpty();
    }

    private long ingest(IngestPolicyRequest request) {
        return ingestion.ingest(request).policyId();
    }

    private long ruleOf(long policyId) {
        return store.findLink(policyId).orElseThrow().ruleId();
    }

    private long newsId(String title) {
        return jdbc.queryForObject("SELECT id FROM policy_documents WHERE title = :title AND source_type = 'SECONDARY'",
                java.util.Map.of("title", title), Long.class);
    }

    private static IngestPolicyRequest primary(String documentNumber, String title, String content) {
        return new IngestPolicyRequest(documentNumber, title, "Homeland Security Department", "F-1,J-1",
                PolicyStatus.FINAL, SourceType.PRIMARY, LocalDate.of(2026, 8, 1), null,
                "https://www.federalregister.gov/d/" + documentNumber, content);
    }
}
