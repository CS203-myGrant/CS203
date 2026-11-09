package mygrant.rules;

import static mygrant.rules.RuleFixtures.COURT_ORDER;
import static mygrant.rules.RuleFixtures.FINAL_EFFECTIVE;
import static mygrant.rules.RuleFixtures.FINAL_RULE;
import static mygrant.rules.RuleFixtures.PROPOSAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import mygrant.auth.JwtService;
import mygrant.common.Confidence;
import mygrant.ingestion.PolicyIngestionService;
import mygrant.ingestion.dto.IngestPolicyRequest;
import mygrant.ingestion.newsrss.NewsRssConnector;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;
import mygrant.testclock.TestClock;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

/**
 * CS203-86: an admin clears the dispute the real court order put on the D/S rule (2026-14439) once
 * a court lifts the block. After that, news never disputes the rule again; only a court order an
 * admin records does. Against PostgreSQL through the endpoints; every test rolls back, and the
 * fixture documents are isolated first so a local copy of them is left alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@RecordApplicationEvents
class RuleDisputeClearingIntegrationTest {

    private static final LocalDate READ_ON = LocalDate.of(2026, 11, 6);
    private static final LocalDate LIFTED_ON = LocalDate.of(2026, 11, 2);
    private static final String LIFTING_ORDER = "ca1-26-1702-2026-11-02";
    private static final String LIFTING_TITLE = "Appeals court lifts the postponement of the duration of status rule";
    private static final String APPEALS_COURT = "U.S. Court of Appeals for the First Circuit";
    private static final String LIFTING_URL = "https://www.ca1.uscourts.gov/opinions/26-1702";
    private static final String REASON = "The First Circuit vacated the postponement; DHS says the rule now applies.";
    /** Synthetic: another rule, disputed by its own report, that clearing the D/S rule must not touch. */
    private static final String OTHER_RULE = "2099-99001";
    private static final String OTHER_REPORT = "synthetic-other-rule-injunction";
    private static final String STALE_REPORT = "synthetic-stale-injunction-report";
    private static final String NEW_REPORT = "synthetic-new-injunction-report";
    private static final String LATER_REPORT = "synthetic-later-injunction-report";
    /** Synthetic: the lifting order, which an admin also posts as a court order, dated on the lift. */
    private static final String LIFT_AS_ORDER = "synthetic-lifting-order-posted-2026-11-02";
    /** Synthetic: a court order an admin records, blocking the rule again after the lift. */
    private static final String NEW_ORDER = "synthetic-new-block-order-2026-11-03";
    /** Synthetic: a rule no admin ever cleared, and the news that disputes it. */
    private static final String NEVER_CLEARED_RULE = "2099-99002";
    private static final String NEVER_CLEARED_REPORT = "synthetic-never-cleared-injunction-report";

    @Autowired PolicyIngestionService ingestion;
    @Autowired NewsRssConnector news;
    @Autowired PolicyRuleStore store;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @Autowired TestClock clock;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ApplicationEvents events;
    @MockBean VectorStore vectors;

    private final List<Long> clockUsers = new ArrayList<>();
    private User admin;
    private User applicant;
    private long finalRule;
    private long ruleId;
    private long otherRuleId;

    @BeforeEach
    void disputeTheFixedTimeRuleAndAnother() throws Exception {
        RuleFixtures.isolate(jdbc, List.of(PROPOSAL, FINAL_RULE, COURT_ORDER, OTHER_RULE, OTHER_REPORT, STALE_REPORT,
                NEW_REPORT, LATER_REPORT, LIFT_AS_ORDER, NEW_ORDER, NEVER_CLEARED_RULE, NEVER_CLEARED_REPORT));
        admin = user(UserRole.ADMIN);
        applicant = user(UserRole.APPLICANT);

        ingestion.ingest(RuleFixtures.proposal());
        finalRule = ingestion.ingest(RuleFixtures.finalRule()).policyId();
        ingestion.ingestRecordedByAdmin(
                json.readValue(RuleFixtures.text(RuleFixtures.COURT_ORDER_REQUEST), IngestPolicyRequest.class));
        ruleId = store.findLink(finalRule).orElseThrow().ruleId();
        assertThat(store.findRule(ruleId).orElseThrow().state())
                .isEqualTo(new RuleState(PolicyStatus.FINAL, FINAL_EFFECTIVE, true));

        long other = ingestion.ingest(new IngestPolicyRequest(OTHER_RULE, "Synthetic reporting rule for F-1 students",
                "Homeland Security Department", "F-1", PolicyStatus.FINAL, SourceType.PRIMARY,
                LocalDate.of(2026, 8, 3), LocalDate.of(2026, 10, 1), "https://www.federalregister.gov/d/" + OTHER_RULE,
                "Synthetic fixture.\n[DHS Docket No. ICEB-2099-0001]\nRIN 1653-ZZ99\nACTION: Final rule.\n"
                        + "SUMMARY: F-1 students report a change of address within five days.")).policyId();
        fromTheNews(report(OTHER_REPORT, "Judge blocks the F-1 address reporting rule", LocalDate.of(2026, 9, 20),
                OTHER_RULE));
        otherRuleId = store.findLink(other).orElseThrow().ruleId();
        assertThat(otherRuleId).isNotEqualTo(ruleId);
        assertThat(store.findRule(otherRuleId).orElseThrow().state().disputed()).isTrue();

        clock.setUserDate(admin.getId(), READ_ON);
        clock.setUserDate(applicant.getId(), READ_ON);
        events.clear();
    }

    @AfterEach
    void resetClock() {
        clockUsers.forEach(id -> clock.setUserDate(id, null));
    }

    @Test
    void anAdminClearsTheDisputeAndTheRuleIsInEffectAgain() throws Exception {
        var otherBefore = store.findRule(otherRuleId).orElseThrow();
        int otherHistory = store.history(otherRuleId).size();

        clear(finalRule, admin, body(LIFTED_ON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleId").value(ruleId))
                .andExpect(jsonPath("$.primaryStage").value("FINAL"))
                .andExpect(jsonPath("$.effectiveDate").value(FINAL_EFFECTIVE.toString()))
                .andExpect(jsonPath("$.disputed").value(false))
                .andExpect(jsonPath("$.clearedBy").value(admin.getEmail()))
                .andExpect(jsonPath("$.clearedAt").isNotEmpty())
                .andExpect(jsonPath("$.reason").value(REASON))
                .andExpect(jsonPath("$.source.policyId").value(nullValue()))
                .andExpect(jsonPath("$.source.externalId").value(LIFTING_ORDER))
                .andExpect(jsonPath("$.source.sourceType").value("SECONDARY"))
                .andExpect(jsonPath("$.source.agency").value(APPEALS_COURT))
                .andExpect(jsonPath("$.source.publishedOn").value(LIFTED_ON.toString()));

        // The flag is cleared and the stage and date are left alone.
        assertThat(store.findRule(ruleId).orElseThrow().state())
                .isEqualTo(new RuleState(PolicyStatus.FINAL, FINAL_EFFECTIVE, false));
        // One append-only step records who, when, why and what lifted the block.
        Map<String, Object> step = jdbc.queryForMap("""
                SELECT * FROM policy_rule_history WHERE rule_id = :id AND recorded_by = 'ADMIN'
                """, Map.of("id", ruleId));
        assertThat(step).containsEntry("actor_email", admin.getEmail())
                .containsEntry("reason", REASON)
                .containsEntry("source_external_id", LIFTING_ORDER)
                .containsEntry("source_type", "SECONDARY")
                .containsEntry("source_title", LIFTING_TITLE)
                .containsEntry("source_agency", APPEALS_COURT)
                .containsEntry("source_url", LIFTING_URL)
                .containsEntry("source_policy_id", null)
                .containsEntry("primary_stage", "FINAL")
                .containsEntry("previous_disputed", true)
                .containsEntry("disputed", false)
                .containsEntry("applied", true)
                .containsEntry("alerted", false);
        assertThat(step.get("recorded_at")).isNotNull();
        assertThat(step.get("observed_on")).hasToString(LIFTED_ON.toString());
        // No alert and no reassessment: nothing is raised.
        assertThat(events.stream(PolicyChangedEvent.class)).isEmpty();
        // Nothing else changes.
        assertThat(store.findRule(otherRuleId).orElseThrow()).isEqualTo(otherBefore);
        assertThat(store.history(otherRuleId)).hasSize(otherHistory);

        // The court order's date has passed: the timeline shows the rule in effect from the day the
        // block was lifted, not from 15 Sep, when it was blocked.
        mvc.perform(get("/api/v1/policies/{id}/timeline", finalRule).header("Authorization", token(applicant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value(READ_ON.toString()))
                .andExpect(jsonPath("$.primaryStage").value("FINAL"))
                .andExpect(jsonPath("$.currentStage").value("EFFECTIVE"))
                .andExpect(jsonPath("$.disputed").value(false))
                .andExpect(jsonPath("$.effectiveDatePassedWhileDisputed").value(false))
                .andExpect(jsonPath("$.disputedBy").value(nullValue()))
                .andExpect(jsonPath("$.steps.length()").value(5))
                .andExpect(jsonPath("$.steps[2].changes").value(contains("DISPUTED")))
                .andExpect(jsonPath("$.steps[2].disputed").value(true))
                .andExpect(jsonPath("$.steps[2].source.externalId").value(COURT_ORDER))
                .andExpect(jsonPath("$.steps[3].changes").value(contains("DISPUTED")))
                .andExpect(jsonPath("$.steps[3].disputed").value(false))
                .andExpect(jsonPath("$.steps[3].primaryStage").value("FINAL"))
                .andExpect(jsonPath("$.steps[3].effectiveDate").value(FINAL_EFFECTIVE.toString()))
                .andExpect(jsonPath("$.steps[3].observedOn").value(LIFTED_ON.toString()))
                .andExpect(jsonPath("$.steps[3].source.externalId").value(LIFTING_ORDER))
                .andExpect(jsonPath("$.steps[3].source.title").value(LIFTING_TITLE))
                .andExpect(jsonPath("$.steps[4].changes").value(contains("TOOK_EFFECT")))
                .andExpect(jsonPath("$.steps[4].derived").value(true))
                .andExpect(jsonPath("$.steps[4].observedOn").value(LIFTED_ON.toString()))
                .andExpect(jsonPath("$.steps[4].disputed").value(false));
    }

    @Test
    void onlyAnAdminCanClearADispute() throws Exception {
        clear(finalRule, applicant, body(LIFTED_ON)).andExpect(status().isForbidden());
        // Rejected before the body is read, so an invalid body is still a 403, not a 400.
        clear(finalRule, applicant, "{}").andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/policies/{id}/dispute/clear", finalRule)
                        .contentType(MediaType.APPLICATION_JSON).content(body(LIFTED_ON)))
                .andExpect(status().isUnauthorized());

        assertThat(store.findRule(ruleId).orElseThrow().state().disputed()).isTrue();
        assertThat(adminSteps()).isZero();
    }

    @Test
    void clearingARuleThatIsNotDisputedIsAConflict() throws Exception {
        clear(finalRule, admin, body(LIFTED_ON)).andExpect(status().isOk());

        clear(finalRule, admin, body(LIFTED_ON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Rule " + ruleId + " is not disputed"));
        assertThat(adminSteps()).isEqualTo(1);
    }

    @Test
    void newsOfTheLiftAfterAClearingIsHeldForReviewAndTheRuleStaysInEffect() throws Exception {
        clear(finalRule, admin, body(LIFTED_ON)).andExpect(status().isOk());
        events.clear();

        // "Appeals court lifts injunction on …", the day after the lift, through the news feed.
        // "injunction" reads as litigation, but the story reports the lift itself.
        news.importSaved(RuleFixtures.text(RuleFixtures.LIFT_FEED));
        long lift = newsId(RuleFixtures.LIFT_HEADLINE);

        assertThat(store.findRule(ruleId).orElseThrow().state())
                .isEqualTo(new RuleState(PolicyStatus.FINAL, FINAL_EFFECTIVE, false));
        var link = store.findLink(lift).orElseThrow();
        assertThat(link.ruleId()).isEqualTo(ruleId);
        assertThat(link.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(link.needsReview()).isTrue();
        assertThat(link.reviewReason()).isEqualTo(heldReason(LIFTED_ON));
        // A step that was not applied says why; the rule is the same before and after it.
        Map<String, Object> held = stepFrom(lift);
        assertThat(held).containsEntry("applied", false)
                .containsEntry("alerted", false)
                .containsEntry("recorded_by", "INGESTION")
                .containsEntry("source_type", "SECONDARY")
                .containsEntry("previous_disputed", false)
                .containsEntry("disputed", false)
                .containsEntry("reason", heldReason(LIFTED_ON))
                .containsEntry("actor_email", null);
        assertThat(held.get("observed_on")).hasToString(RuleFixtures.LIFT_NEWS_PUBLISHED.toString());
        // No change event, so no "rule changed" alert and no reassessment. Only the review flag is
        // raised, as for any document held for review, and nothing alerts on it.
        assertThat(events.stream(PolicyChangedEvent.class)).isEmpty();
        assertThat(events.stream(RuleLinkFlaggedEvent.class)).singleElement().satisfies(flag -> {
            assertThat(flag.policyId()).isEqualTo(lift);
            assertThat(flag.ruleId()).isEqualTo(ruleId);
            assertThat(flag.reason()).isEqualTo(heldReason(LIFTED_ON));
        });

        // The timeline agrees with the rule: in effect since the lift. The held report is in the
        // documents, flagged, like any document held for review, and is not a step.
        mvc.perform(get("/api/v1/policies/{id}/timeline", lift).header("Authorization", token(applicant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleId").value(ruleId))
                .andExpect(jsonPath("$.currentStage").value("EFFECTIVE"))
                .andExpect(jsonPath("$.disputed").value(false))
                .andExpect(jsonPath("$.effectiveDatePassedWhileDisputed").value(false))
                .andExpect(jsonPath("$.disputedBy").value(nullValue()))
                .andExpect(jsonPath("$.steps[*].source.externalId").value(contains(PROPOSAL, FINAL_RULE, COURT_ORDER,
                        LIFTING_ORDER, FINAL_RULE)))
                .andExpect(jsonPath("$.steps[4].changes").value(contains("TOOK_EFFECT")))
                .andExpect(jsonPath("$.steps[4].observedOn").value(LIFTED_ON.toString()))
                .andExpect(jsonPath("$.documents[?(@.policyId == %d)].needsReview", lift).value(contains(true)))
                .andExpect(jsonPath("$.documents[?(@.policyId == %d)].reviewReason", lift)
                        .value(contains(heldReason(LIFTED_ON))));
    }

    @Test
    void aNewBlockReportedOnlyInTheNewsIsHeldToo() throws Exception {
        clear(finalRule, admin, body(LIFTED_ON)).andExpect(status().isOk());
        events.clear();

        // Before the lift or after it, news no longer disputes the rule.
        long stale = fromTheNews(report(STALE_REPORT, "Court blocks duration of status rule, students told",
                LocalDate.of(2026, 10, 1), FINAL_RULE));
        long fresh = fromTheNews(report(NEW_REPORT, "Judge blocks the duration of status rule again",
                LocalDate.of(2026, 11, 4), FINAL_RULE));

        assertThat(store.findRule(ruleId).orElseThrow().state().disputed()).isFalse();
        for (long report : List.of(stale, fresh)) {
            assertThat(store.findLink(report).orElseThrow().reviewReason()).isEqualTo(heldReason(LIFTED_ON));
            assertThat(stepFrom(report)).containsEntry("applied", false).containsEntry("disputed", false)
                    .containsEntry("alerted", false);
        }
        assertThat(events.stream(PolicyChangedEvent.class)).isEmpty();
        mvc.perform(get("/api/v1/policies/{id}/timeline", finalRule).header("Authorization", token(applicant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStage").value("EFFECTIVE"))
                .andExpect(jsonPath("$.disputed").value(false))
                .andExpect(jsonPath("$.steps[*].source.externalId").value(contains(PROPOSAL, FINAL_RULE, COURT_ORDER,
                        LIFTING_ORDER, FINAL_RULE)))
                .andExpect(jsonPath("$.documents[?(@.needsReview == true)].externalId")
                        .value(containsInAnyOrder(STALE_REPORT, NEW_REPORT)));
    }

    @Test
    void aCourtOrderAnAdminRecordsDisputesTheRuleAgainAndASecondClearingHoldsNewsAgain() throws Exception {
        clear(finalRule, admin, body(LIFTED_ON)).andExpect(status().isOk());
        events.clear();

        // The lifting order posted as a court order as well: dated on the lift, it is about the
        // block that was lifted, so even an admin's is held.
        long liftAsOrder = recordedByAdmin(report(LIFT_AS_ORDER,
                "Appeals court vacated the postponement of the duration of status rule", LIFTED_ON, FINAL_RULE));
        assertThat(store.findRule(ruleId).orElseThrow().state().disputed()).isFalse();
        assertThat(store.findLink(liftAsOrder).orElseThrow().reviewReason()).isEqualTo("Litigation reported, but not "
                + "applied: published on or before " + LIFTED_ON + ", the date of the source an admin cleared the "
                + "dispute with.");
        assertThat(stepFrom(liftAsOrder)).containsEntry("applied", false).containsEntry("disputed", false);
        // News of a new block is held, as after any clearing.
        long newsOfBlock = fromTheNews(report(NEW_REPORT, "Judge blocks the duration of status rule again",
                LocalDate.of(2026, 11, 5), FINAL_RULE));
        assertThat(store.findLink(newsOfBlock).orElseThrow().reviewReason()).isEqualTo(heldReason(LIFTED_ON));
        assertThat(events.stream(PolicyChangedEvent.class)).isEmpty();

        // The court order the admin records disputes the rule again, with the usual change event and alert.
        long newOrder = recordedByAdmin(report(NEW_ORDER, "Federal court blocks the duration of status rule again",
                LocalDate.of(2026, 11, 3), FINAL_RULE));
        assertThat(store.findRule(ruleId).orElseThrow().state())
                .isEqualTo(new RuleState(PolicyStatus.FINAL, FINAL_EFFECTIVE, true));
        assertThat(store.findLink(newOrder).orElseThrow().needsReview()).isFalse();
        assertThat(stepFrom(newOrder)).containsEntry("applied", true).containsEntry("alerted", true)
                .containsEntry("reason", null);
        assertThat(events.stream(PolicyChangedEvent.class)).singleElement().satisfies(change -> {
            assertThat(change.changes()).containsExactly(RuleChange.DISPUTED);
            assertThat(change.source().externalId()).isEqualTo(NEW_ORDER);
        });
        mvc.perform(get("/api/v1/policies/{id}/timeline", finalRule).header("Authorization", token(applicant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStage").value("FINAL"))
                .andExpect(jsonPath("$.disputed").value(true))
                .andExpect(jsonPath("$.effectiveDatePassedWhileDisputed").value(true))
                .andExpect(jsonPath("$.disputedBy.externalId").value(NEW_ORDER))
                // Disputed again, the rule has no TOOK_EFFECT step; the held reports are not steps.
                .andExpect(jsonPath("$.steps[*].source.externalId").value(contains(PROPOSAL, FINAL_RULE, COURT_ORDER,
                        LIFTING_ORDER, NEW_ORDER)))
                .andExpect(jsonPath("$.steps[4].disputed").value(true));

        // Cleared again once that block is lifted. The news held on 5 Nov never disputed the rule,
        // so a lift dated 4 Nov is not before the report that did.
        clear(finalRule, admin, body(LocalDate.of(2026, 11, 4))).andExpect(status().isOk());
        assertThat(store.findRule(ruleId).orElseThrow().state().disputed()).isFalse();
        assertThat(adminSteps()).isEqualTo(2);
        events.clear();

        // And later news is held again.
        long later = fromTheNews(report(LATER_REPORT, "Judge blocks the duration of status rule once more", READ_ON,
                FINAL_RULE));
        assertThat(store.findRule(ruleId).orElseThrow().state().disputed()).isFalse();
        assertThat(store.findLink(later).orElseThrow().reviewReason()).isEqualTo(heldReason(LocalDate.of(2026, 11, 4)));
        assertThat(stepFrom(later)).containsEntry("applied", false);
        assertThat(events.stream(PolicyChangedEvent.class)).isEmpty();
        mvc.perform(get("/api/v1/policies/{id}/timeline", finalRule).header("Authorization", token(applicant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStage").value("EFFECTIVE"))
                .andExpect(jsonPath("$.disputedBy").value(nullValue()))
                // In effect from the first lift, blocked again on 3 Nov, lifted on 4 Nov.
                .andExpect(jsonPath("$.steps[*].source.externalId").value(contains(PROPOSAL, FINAL_RULE, COURT_ORDER,
                        LIFTING_ORDER, FINAL_RULE, NEW_ORDER, LIFTING_ORDER)))
                .andExpect(jsonPath("$.steps[*].disputed").value(contains(false, false, true, false, false, true,
                        false)));
    }

    @Test
    void newsStillDisputesARuleNoAdminHasCleared() throws Exception {
        // Clearing the D/S rule changes nothing for a rule nobody cleared.
        clear(finalRule, admin, body(LIFTED_ON)).andExpect(status().isOk());
        long rule = ingestion.ingest(new IngestPolicyRequest(NEVER_CLEARED_RULE,
                "Synthetic program reporting rule for J-1 sponsors", "Homeland Security Department", "J-1",
                PolicyStatus.FINAL, SourceType.PRIMARY, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 10, 1),
                "https://www.federalregister.gov/d/" + NEVER_CLEARED_RULE,
                "Synthetic fixture.\n[DHS Docket No. ICEB-2099-0002]\nRIN 1653-ZZ98\nACTION: Final rule.\n"
                        + "SUMMARY: J-1 sponsors report program changes within ten days.")).policyId();
        long neverCleared = store.findLink(rule).orElseThrow().ruleId();
        events.clear();

        long report = fromTheNews(report(NEVER_CLEARED_REPORT, "Judge blocks the J-1 sponsor reporting rule",
                LocalDate.of(2026, 11, 4), NEVER_CLEARED_RULE));

        assertThat(store.findRule(neverCleared).orElseThrow().state().disputed()).isTrue();
        assertThat(store.findLink(report).orElseThrow().needsReview()).isFalse();
        assertThat(stepFrom(report)).containsEntry("applied", true).containsEntry("alerted", true)
                .containsEntry("disputed", true).containsEntry("reason", null);
        assertThat(events.stream(PolicyChangedEvent.class)).singleElement().satisfies(change -> {
            assertThat(change.ruleId()).isEqualTo(neverCleared);
            assertThat(change.changes()).containsExactly(RuleChange.DISPUTED);
            assertThat(change.source().externalId()).isEqualTo(NEVER_CLEARED_REPORT);
        });
        assertThat(store.findRule(ruleId).orElseThrow().state().disputed()).isFalse();
    }

    @Test
    void anInvalidRequestIsRejectedAndChangesNothing() throws Exception {
        clear(finalRule, admin, """
                {"source": {"externalId": "x", "title": "t", "agency": "a", "sourceUrl": "https://example.org",
                            "publicationDate": "2026-11-02"}}
                """).andExpect(status().isBadRequest());
        clear(finalRule, admin, body(LIFTED_ON).replace(LIFTING_URL, "file:///tmp/order.pdf"))
                .andExpect(status().isBadRequest());
        // Dated before the court order that disputed the rule.
        clear(finalRule, admin, body(LocalDate.of(2026, 9, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("source.publicationDate 2026-09-01 is before the report that "
                        + "disputed the rule, published " + RuleFixtures.COURT_ORDER_PUBLISHED));
        // Dated after the admin's own date.
        clear(finalRule, admin, body(READ_ON.plusDays(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("source.publicationDate " + READ_ON.plusDays(1)
                        + " is after today, " + READ_ON));
        clear(Long.MAX_VALUE, admin, body(LIFTED_ON)).andExpect(status().isNotFound());

        assertThat(store.findRule(ruleId).orElseThrow().state().disputed()).isTrue();
        assertThat(adminSteps()).isZero();
    }

    private ResultActions clear(long policyId, User user, String body) throws Exception {
        return mvc.perform(post("/api/v1/policies/{id}/dispute/clear", policyId).header("Authorization", token(user))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String body(LocalDate liftedOn) {
        return """
                {
                  "reason": "%s",
                  "source": {
                    "externalId": "%s",
                    "title": "%s",
                    "agency": "%s",
                    "sourceUrl": "%s",
                    "publicationDate": "%s"
                  }
                }
                """.formatted(REASON, LIFTING_ORDER, LIFTING_TITLE, APPEALS_COURT, LIFTING_URL, liftedOn);
    }

    /** A secondary source linked to a rule by its Federal Register document, reporting a court action. */
    private static IngestPolicyRequest report(String externalId, String title, LocalDate published,
            String documentNumber) {
        return new IngestPolicyRequest(externalId, title, "Synthetic Student News", "F-1", PolicyStatus.CHALLENGED,
                SourceType.SECONDARY, published, null, "https://news.example.org/" + externalId,
                title + ". Synthetic report for tests.", Set.of(), Set.of(),
                List.of("https://www.federalregister.gov/d/" + documentNumber));
    }

    /** How the news connector imports an article: not marked as an admin's. */
    private long fromTheNews(IngestPolicyRequest report) {
        return ingestion.ingest(report, report.content(), Set.of()).policyId();
    }

    /** How an admin records a court order: POST /api/v1/ingestion/documents. */
    private long recordedByAdmin(IngestPolicyRequest order) throws Exception {
        String created = mvc.perform(post("/api/v1/ingestion/documents").header("Authorization", token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(order)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(created).path("policyId").asLong();
    }

    private static String heldReason(LocalDate liftedOn) {
        return "Litigation reported, but held for review: an admin cleared this rule's dispute (block lifted "
                + liftedOn + "), and news no longer disputes it. If this reports a new block, record the court order.";
    }

    /** The history step a document wrote. */
    private Map<String, Object> stepFrom(long policyId) {
        return jdbc.queryForMap("SELECT * FROM policy_rule_history WHERE source_policy_id = :id",
                Map.of("id", policyId));
    }

    private long newsId(String title) {
        return jdbc.queryForObject("SELECT id FROM policy_documents WHERE title = :title AND source_type = 'SECONDARY'",
                Map.of("title", title), Long.class);
    }

    private int adminSteps() {
        return jdbc.queryForObject("SELECT count(*) FROM policy_rule_history WHERE rule_id = :id AND recorded_by = 'ADMIN'",
                Map.of("id", ruleId), Integer.class);
    }

    private User user(UserRole role) {
        String suffix = UUID.randomUUID().toString();
        User user = new User("Dispute test", "rule-dispute-" + suffix + "@example.com", null);
        user.setRole(role);
        user.setWorkosUserId("user_rule_dispute_" + suffix);
        user.setProfileComplete(true);
        user = users.saveAndFlush(user);
        clockUsers.add(user.getId());
        return user;
    }

    private String token(User user) {
        return "Bearer " + jwt.generateAccessToken(user);
    }
}
