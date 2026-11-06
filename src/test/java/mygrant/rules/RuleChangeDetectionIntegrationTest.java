package mygrant.rules;

import static mygrant.rules.RuleFixtures.DATE_MOVE;
import static mygrant.rules.RuleFixtures.FINAL_EFFECTIVE;
import static mygrant.rules.RuleFixtures.FINAL_RULE;
import static mygrant.rules.RuleFixtures.H1B_FINAL_RULE;
import static mygrant.rules.RuleFixtures.H1B_PROPOSAL;
import static mygrant.rules.RuleFixtures.H1B_REGISTRATION_RULE;
import static mygrant.rules.RuleFixtures.MOVED_EFFECTIVE;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import mygrant.common.Confidence;
import mygrant.ingestion.PolicyIngestionService;
import mygrant.ingestion.dto.IngestPolicyRequest;
import mygrant.ingestion.newsrss.NewsRssConnector;
import mygrant.policies.PolicyIndexedEvent;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;

/**
 * CS203-135 against PostgreSQL: which documents raise a PolicyChangedEvent, with what values.
 * Rolls back, so no listener runs; RuleLifecycleIntegrationTest covers the alerts end to end.
 */
@SpringBootTest
@Transactional
@RecordApplicationEvents
class RuleChangeDetectionIntegrationTest {

    /** Synthetic: a notice in the docket, published before the proposal. */
    private static final String NOTICE = "2025-99901";
    /** Synthetic: a delay notice whose DATES gives no new date. */
    private static final String UNREAD_DELAY = "2026-99951";

    @Autowired PolicyIngestionService ingestion;
    @Autowired NewsRssConnector news;
    @Autowired PolicyRuleStore store;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired ApplicationEvents events;
    @MockBean VectorStore vectors;

    @BeforeEach
    void isolateFixtures() {
        RuleFixtures.isolate(jdbc, List.of(PROPOSAL, FINAL_RULE, OLD_PROPOSAL, DATE_MOVE, NOTICE, UNREAD_DELAY,
                H1B_PROPOSAL, H1B_REGISTRATION_RULE, H1B_FINAL_RULE));
    }

    @Test
    void theFinalRuleRaisesOneChangeWithOldAndNewValuesAndItsSource() {
        long proposal = ingest(RuleFixtures.proposal());
        long finalRule = ingest(RuleFixtures.finalRule());
        long ruleId = store.findLink(finalRule).orElseThrow().ruleId();

        assertThat(changes()).singleElement().satisfies(change -> {
            assertThat(change.ruleId()).isEqualTo(ruleId);
            assertThat(change.ruleTitle()).isEqualTo(RuleFixtures.TITLE);
            assertThat(change.previous()).isEqualTo(new RuleState(PolicyStatus.PROPOSED, null, false));
            assertThat(change.current()).isEqualTo(new RuleState(PolicyStatus.FINAL, FINAL_EFFECTIVE, false));
            assertThat(change.changes()).containsExactlyInAnyOrder(RuleChange.STAGE, RuleChange.EFFECTIVE_DATE);
            assertThat(change.source().policyId()).isEqualTo(finalRule);
            assertThat(change.source().externalId()).isEqualTo(FINAL_RULE);
            assertThat(change.source().sourceType()).isEqualTo(SourceType.PRIMARY);
            assertThat(change.source().url()).isEqualTo("https://www.federalregister.gov/d/" + FINAL_RULE);
            assertThat(change.visaTypes()).containsExactly("F-1", "J-1");
            assertThat(change.primaryContent()).contains("ACTION: Final rule.");
            var row = store.history(ruleId).getLast();
            assertThat(change.historyId()).isEqualTo(row.id());
            assertThat(row.alerted()).isTrue();
        });
        // The final rule's generic alert is replaced by the change alert; the proposal keeps its own.
        assertThat(events.stream(PolicyIndexedEvent.class))
                .extracting(PolicyIndexedEvent::policyId, PolicyIndexedEvent::ruleChanged)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(proposal, false),
                        org.assertj.core.groups.Tuple.tuple(finalRule, true));
    }

    @Test
    void aRulesFirstDocumentAndAnUnchangedRepeatRaiseNothing() {
        ingest(RuleFixtures.proposal());
        assertThat(changes()).isEmpty();
        ingest(RuleFixtures.finalRule());
        events.clear();

        assertThat(ingestion.ingest(RuleFixtures.proposal()).alreadyIndexed()).isTrue();
        assertThat(ingestion.ingest(RuleFixtures.finalRule()).alreadyIndexed()).isTrue();

        assertThat(changes()).isEmpty();
        assertThat(events.stream(PolicyIndexedEvent.class)).isEmpty();
    }

    @Test
    void theSyntheticDelayNoticeMovesTheEffectiveDate() {
        ingest(RuleFixtures.proposal());
        ingest(RuleFixtures.finalRule());
        events.clear();
        long delay = ingest(RuleFixtures.dateMove());

        assertThat(store.findLink(delay).orElseThrow().method()).isEqualTo(MatchMethod.DOCKET_AND_RIN);
        assertThat(changes()).singleElement().satisfies(change -> {
            assertThat(change.changes()).containsExactly(RuleChange.EFFECTIVE_DATE);
            assertThat(change.previous()).isEqualTo(new RuleState(PolicyStatus.FINAL, FINAL_EFFECTIVE, false));
            assertThat(change.current()).isEqualTo(new RuleState(PolicyStatus.FINAL, MOVED_EFFECTIVE, false));
            assertThat(change.source().externalId()).isEqualTo(DATE_MOVE);
        });
        assertThat(store.history(store.findLink(delay).orElseThrow().ruleId()).getLast().aboutEffectiveDate()).isTrue();
    }

    @Test
    void aDelayNoticeWhoseNewDateCannotBeReadIsRecordedAndFlaggedAndChangesNothing() {
        ingest(RuleFixtures.proposal());
        long finalRule = ingest(RuleFixtures.finalRule());
        long ruleId = store.findLink(finalRule).orElseThrow().ruleId();
        int history = store.history(ruleId).size();
        events.clear();
        String dates = "The effective date of the final rule published July 17, 2026, is delayed pending review.";

        // effective_on is the notice's own date, as for a real delay notice; it must not become the rule's.
        long notice = ingest(new IngestPolicyRequest(UNREAD_DELAY, RuleFixtures.TITLE + "; Delay of Effective Date",
                "Homeland Security Department", "F-1,J-1", PolicyStatus.FINAL, SourceType.PRIMARY,
                RuleFixtures.DATE_MOVE_PUBLISHED, RuleFixtures.DATE_MOVE_PUBLISHED,
                "https://www.federalregister.gov/d/" + UNREAD_DELAY,
                "[DHS Docket No. ICEB-2025-0001]\nRIN 1653-AA95\n\nACTION: Final rule; delay of effective date.\n\n"
                        + "SUMMARY: DHS is delaying the final rule.\n\nDATES: " + dates
                        + "\n\nFOR FURTHER INFORMATION CONTACT: DHS.\n"));

        assertThat(changes()).isEmpty();
        assertThat(store.findRule(ruleId).orElseThrow().state())
                .isEqualTo(new RuleState(PolicyStatus.FINAL, FINAL_EFFECTIVE, false));
        // Its step is recorded, applied in order but changing nothing.
        assertThat(store.history(ruleId)).hasSize(history + 1).last().satisfies(row -> {
            assertThat(row.source().externalId()).isEqualTo(UNREAD_DELAY);
            assertThat(row.state()).isEqualTo(new RuleState(PolicyStatus.FINAL, FINAL_EFFECTIVE, false));
            assertThat(row.aboutEffectiveDate()).isTrue();
            assertThat(row.alerted()).isFalse();
        });
        String reason = "Effective date not changed: this document is about the effective date, but no new date "
                + "could be read from its DATES paragraph. DATES: " + dates;
        var link = store.findLink(notice).orElseThrow();
        assertThat(link.ruleId()).isEqualTo(ruleId);
        assertThat(link.needsReview()).isTrue();
        assertThat(link.reviewReason()).isEqualTo(reason);
        assertThat(events.stream(RuleLinkFlaggedEvent.class)).singleElement()
                .satisfies(flag -> assertThat(flag.reason()).isEqualTo(reason));
        // No change alert; the generic alert, which then leaves out effective_on, is the only one.
        assertThat(events.stream(PolicyIndexedEvent.class)).singleElement()
                .satisfies(indexed -> assertThat(indexed.ruleChanged()).isFalse());
    }

    @Test
    void aSecondFinalRuleInTheRulemakingRaisesNothingAndKeepsTheRulesDate() {
        ingest(RuleFixtures.h1b(H1B_PROPOSAL, RuleFixtures.H1B_TITLE, PolicyStatus.PROPOSED,
                RuleFixtures.H1B_PROPOSAL_PUBLISHED, null));
        ingest(RuleFixtures.h1b(H1B_REGISTRATION_RULE, RuleFixtures.H1B_REGISTRATION_TITLE, PolicyStatus.FINAL,
                RuleFixtures.H1B_REGISTRATION_PUBLISHED, RuleFixtures.H1B_REGISTRATION_EFFECTIVE));
        assertThat(changes()).singleElement().satisfies(change -> assertThat(change.current())
                .isEqualTo(new RuleState(PolicyStatus.FINAL, RuleFixtures.H1B_REGISTRATION_EFFECTIVE, false)));
        events.clear();

        long second = ingest(RuleFixtures.h1b(H1B_FINAL_RULE, RuleFixtures.H1B_TITLE, PolicyStatus.FINAL,
                RuleFixtures.H1B_FINAL_PUBLISHED, RuleFixtures.H1B_FINAL_EFFECTIVE));
        long ruleId = store.findLink(second).orElseThrow().ruleId();

        assertThat(changes()).isEmpty();
        // So it gets the usual new-policy alert.
        assertThat(events.stream(PolicyIndexedEvent.class)).singleElement()
                .satisfies(indexed -> assertThat(indexed.ruleChanged()).isFalse());
        assertThat(store.findLink(second).orElseThrow().method()).isEqualTo(MatchMethod.DOCKET_AND_RIN);
        assertThat(store.findRule(ruleId).orElseThrow().state())
                .isEqualTo(new RuleState(PolicyStatus.FINAL, RuleFixtures.H1B_REGISTRATION_EFFECTIVE, false));
        assertThat(store.findRule(ruleId).orElseThrow().primaryAsOf()).isEqualTo(RuleFixtures.H1B_FINAL_PUBLISHED);
        assertThat(store.history(ruleId)).hasSize(3).last().satisfies(row -> {
            assertThat(row.source().externalId()).isEqualTo(H1B_FINAL_RULE);
            assertThat(row.state()).isEqualTo(new RuleState(PolicyStatus.FINAL, RuleFixtures.H1B_FINAL_EFFECTIVE, false));
            assertThat(row.previous())
                    .isEqualTo(new RuleState(PolicyStatus.FINAL, RuleFixtures.H1B_REGISTRATION_EFFECTIVE, false));
            assertThat(row.applied()).isTrue();
            assertThat(row.alerted()).isFalse();
            assertThat(row.aboutEffectiveDate()).isFalse();
        });
    }

    @Test
    void newsOfAnInjunctionMarksTheRuleDisputedAndKeepsItsPrimaryStage() {
        ingest(RuleFixtures.proposal());
        long finalRule = ingest(RuleFixtures.finalRule());
        long ruleId = store.findLink(finalRule).orElseThrow().ruleId();
        events.clear();

        news.importSaved(RuleFixtures.text(RuleFixtures.NEWS_FEED));

        assertThat(store.findRule(ruleId).orElseThrow().state())
                .isEqualTo(new RuleState(PolicyStatus.FINAL, FINAL_EFFECTIVE, true));
        assertThat(changes()).singleElement().satisfies(change -> {
            assertThat(change.changes()).containsExactly(RuleChange.DISPUTED);
            assertThat(change.current().primaryStage()).isEqualTo(PolicyStatus.FINAL);
            assertThat(change.source().sourceType()).isEqualTo(SourceType.SECONDARY);
            assertThat(change.source().title()).isEqualTo(RuleFixtures.INJUNCTION_HEADLINE);
            assertThat(change.source().agency()).isEqualTo("The Guardian");
            // Who is alerted comes from the rule's primary document, not the article.
            assertThat(change.visaTypes()).containsExactly("F-1", "J-1");
            assertThat(change.primaryTitle()).isEqualTo(RuleFixtures.TITLE);
        });
        // The explainer links to the rule too, but mentions only a "length of stay": no dispute.
        assertThat(store.history(ruleId)).filteredOn(row -> row.source().sourceType() == SourceType.SECONDARY)
                .singleElement().satisfies(row -> assertThat(row.source().title()).isEqualTo(RuleFixtures.INJUNCTION_HEADLINE));
    }

    @Test
    void aSecondLitigationReportChangesNothing() {
        long finalRule = ingest(RuleFixtures.finalRule());
        news.importSaved(RuleFixtures.text(RuleFixtures.NEWS_FEED));
        long ruleId = store.findLink(finalRule).orElseThrow().ruleId();
        int history = store.history(ruleId).size();
        events.clear();

        news.importSaved(feed("Court stays student admission rule", "oct/05/court-stays-rule",
                "<p>An appeals court refused to lift the injunction against the "
                        + "<a href=\"https://www.federalregister.gov/d/2026-14439\">rule</a> for F-1 students.</p>"));

        assertThat(changes()).isEmpty();
        assertThat(store.history(ruleId)).hasSize(history);
    }

    @Test
    void litigationOnAnUnconfirmedLinkIsFlaggedButNotApplied() {
        long finalRule = ingest(RuleFixtures.finalRule());
        ingest(RuleFixtures.oldProposal());
        long ruleId = store.findLink(finalRule).orElseThrow().ruleId();
        events.clear();

        // Links to both rulemakings, so which one is disputed is not certain.
        news.importSaved(feed("Lawsuit over F-1 student admission rules", "oct/06/lawsuit-student-rules",
                "<p>F-1 students sued over the <a href=\"https://www.federalregister.gov/d/2026-14439\">rule</a> and the "
                        + "<a href=\"https://www.regulations.gov/docket/ICEB-2019-0006\">earlier proposal</a>.</p>"));

        assertThat(changes()).isEmpty();
        assertThat(store.findRule(ruleId).orElseThrow().state().disputed()).isFalse();
        long article = jdbc.queryForObject("SELECT id FROM policy_documents WHERE title = :title",
                Map.of("title", "Lawsuit over F-1 student admission rules"), Long.class);
        var link = store.findLink(article).orElseThrow();
        assertThat(link.confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(link.reviewReason()).endsWith(PolicyRuleTracker.UNCONFIRMED_LITIGATION);
        assertThat(events.stream(RuleLinkFlaggedEvent.class)).singleElement()
                .satisfies(flag -> assertThat(flag.reason()).endsWith(PolicyRuleTracker.UNCONFIRMED_LITIGATION));
    }

    @Test
    void aNoticeGivingARuleItsFirstStageIsNotAChange() {
        ingest(new IngestPolicyRequest(NOTICE, RuleFixtures.TITLE, "Homeland Security Department", "F-1,J-1",
                PolicyStatus.UNKNOWN, SourceType.PRIMARY, RuleFixtures.PROPOSAL_PUBLISHED.minusDays(30), null,
                "https://www.federalregister.gov/d/" + NOTICE,
                "[DHS Docket No. ICEB-2025-0001]\nRIN 1653-AA95\n\nSUMMARY: Information collection notice."));
        ingest(RuleFixtures.proposal());

        assertThat(changes()).isEmpty();
        assertThat(events.stream(PolicyIndexedEvent.class)).extracting(PolicyIndexedEvent::ruleChanged)
                .containsExactly(false, false);
    }

    private List<PolicyChangedEvent> changes() {
        return events.stream(PolicyChangedEvent.class).toList();
    }

    private long ingest(IngestPolicyRequest request) {
        return ingestion.ingest(request).policyId();
    }

    private static String feed(String title, String path, String descriptionHtml) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel><title>Test</title>
                  <item>
                    <title>%s</title>
                    <link>https://www.theguardian.com/us-news/2026/%s</link>
                    <description><![CDATA[%s]]></description>
                    <pubDate>Mon, 05 Oct 2026 12:00:00 GMT</pubDate>
                  </item>
                </channel></rss>
                """.formatted(title, path, descriptionHtml);
    }
}
