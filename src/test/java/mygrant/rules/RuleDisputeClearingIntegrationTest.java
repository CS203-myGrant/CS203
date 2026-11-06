package mygrant.rules;

import static mygrant.rules.RuleFixtures.COURT_ORDER;
import static mygrant.rules.RuleFixtures.FINAL_EFFECTIVE;
import static mygrant.rules.RuleFixtures.FINAL_RULE;
import static mygrant.rules.RuleFixtures.PROPOSAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
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
import mygrant.ingestion.PolicyIngestionService;
import mygrant.ingestion.dto.IngestPolicyRequest;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;
import mygrant.testclock.TestClock;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

/**
 * CS203-86: an admin clears the dispute the real court order put on the D/S rule (2026-14439) once
 * a court lifts the block. Against PostgreSQL through the endpoints; every test rolls back, and
 * the fixture documents are isolated first so a local copy of them is left alone.
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

    @Autowired PolicyIngestionService ingestion;
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
                NEW_REPORT));
        admin = user(UserRole.ADMIN);
        applicant = user(UserRole.APPLICANT);

        ingestion.ingest(RuleFixtures.proposal());
        finalRule = ingestion.ingest(RuleFixtures.finalRule()).policyId();
        ingestion.ingest(json.readValue(RuleFixtures.text(RuleFixtures.COURT_ORDER_REQUEST), IngestPolicyRequest.class));
        ruleId = store.findLink(finalRule).orElseThrow().ruleId();
        assertThat(store.findRule(ruleId).orElseThrow().state())
                .isEqualTo(new RuleState(PolicyStatus.FINAL, FINAL_EFFECTIVE, true));

        long other = ingestion.ingest(new IngestPolicyRequest(OTHER_RULE, "Synthetic reporting rule for F-1 students",
                "Homeland Security Department", "F-1", PolicyStatus.FINAL, SourceType.PRIMARY,
                LocalDate.of(2026, 8, 3), LocalDate.of(2026, 10, 1), "https://www.federalregister.gov/d/" + OTHER_RULE,
                "Synthetic fixture.\n[DHS Docket No. ICEB-2099-0001]\nRIN 1653-ZZ99\nACTION: Final rule.\n"
                        + "SUMMARY: F-1 students report a change of address within five days.")).policyId();
        ingestion.ingest(report(OTHER_REPORT, "Judge blocks the F-1 address reporting rule", LocalDate.of(2026, 9, 20),
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
    void aLaterCourtActionDisputesTheRuleAgain() throws Exception {
        clear(finalRule, admin, body(LIFTED_ON)).andExpect(status().isOk());

        // A report published before the block was lifted is about the old block: flagged, not applied.
        long stale = ingestion.ingest(report(STALE_REPORT, "Court blocks duration of status rule, students told",
                LocalDate.of(2026, 10, 1), FINAL_RULE)).policyId();
        assertThat(store.findRule(ruleId).orElseThrow().state().disputed()).isFalse();
        var staleLink = store.findLink(stale).orElseThrow();
        assertThat(staleLink.ruleId()).isEqualTo(ruleId);
        assertThat(staleLink.needsReview()).isTrue();
        assertThat(staleLink.reviewReason()).isEqualTo("Litigation reported, but not applied: published on or before "
                + LIFTED_ON + ", the date of the source an admin cleared the dispute with.");
        assertThat(events.stream(PolicyChangedEvent.class)).isEmpty();

        // A new order after the lift disputes it again, with the usual change event and alert.
        long fresh = ingestion.ingest(report(NEW_REPORT, "Judge blocks the duration of status rule again",
                LocalDate.of(2026, 11, 4), FINAL_RULE)).policyId();
        assertThat(store.findRule(ruleId).orElseThrow().state())
                .isEqualTo(new RuleState(PolicyStatus.FINAL, FINAL_EFFECTIVE, true));
        assertThat(store.findLink(fresh).orElseThrow().needsReview()).isFalse();
        assertThat(events.stream(PolicyChangedEvent.class)).singleElement().satisfies(change -> {
            assertThat(change.changes()).containsExactly(RuleChange.DISPUTED);
            assertThat(change.source().externalId()).isEqualTo(NEW_REPORT);
        });

        mvc.perform(get("/api/v1/policies/{id}/timeline", finalRule).header("Authorization", token(applicant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStage").value("FINAL"))
                .andExpect(jsonPath("$.disputed").value(true))
                .andExpect(jsonPath("$.effectiveDatePassedWhileDisputed").value(true))
                .andExpect(jsonPath("$.disputedBy.externalId").value(NEW_REPORT))
                // The stale report is flagged, not a step; disputed again, the rule has no TOOK_EFFECT step.
                .andExpect(jsonPath("$.steps[*].source.externalId").value(contains(PROPOSAL, FINAL_RULE, COURT_ORDER,
                        LIFTING_ORDER, NEW_REPORT)))
                .andExpect(jsonPath("$.steps[*].changes[*]").value(not(hasItem("TOOK_EFFECT"))))
                .andExpect(jsonPath("$.steps[4].disputed").value(true));

        // And an admin can clear it again once that block is lifted too.
        clear(finalRule, admin, body(LocalDate.of(2026, 11, 5))).andExpect(status().isOk());
        assertThat(store.findRule(ruleId).orElseThrow().state().disputed()).isFalse();
        assertThat(adminSteps()).isEqualTo(2);
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

    private int adminSteps() {
        return jdbc.queryForObject("SELECT count(*) FROM policy_rule_history WHERE rule_id = :id AND recorded_by = 'ADMIN'",
                Map.of("id", ruleId), Integer.class);
    }

    private User user(UserRole role) {
        String suffix = UUID.randomUUID().toString();
        User user = new User("Dispute test", "rule-dispute-" + suffix + "@example.com", null, "F-1", null, null);
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
