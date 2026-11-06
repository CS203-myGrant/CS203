package mygrant.rules;

import static mygrant.rules.RuleFixtures.AC61_DELAY;
import static mygrant.rules.RuleFixtures.AC61_FINAL_RULE;
import static mygrant.rules.RuleFixtures.AC61_PROPOSAL;
import static mygrant.rules.RuleFixtures.AC61_TITLE;
import static mygrant.rules.RuleFixtures.AC61_WITHDRAWAL;
import static mygrant.rules.RuleFixtures.COURT_ORDER;
import static mygrant.rules.RuleFixtures.DATE_MOVE;
import static mygrant.rules.RuleFixtures.FINAL_RULE;
import static mygrant.rules.RuleFixtures.H1B_FINAL_RULE;
import static mygrant.rules.RuleFixtures.H1B_PROPOSAL;
import static mygrant.rules.RuleFixtures.H1B_REGISTRATION_RULE;
import static mygrant.rules.RuleFixtures.PROPOSAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import mygrant.auth.JwtService;
import mygrant.email.DailyDigestService;
import mygrant.email.ImmediateAlertQueue;
import mygrant.impact.ImpactUrgency;
import mygrant.impact.JudgeEvaluation;
import mygrant.impact.ProfileImpactAssessment;
import mygrant.impact.ProfileImpactAssessmentService;
import mygrant.ingestion.federalregister.FederalRegisterClient;
import mygrant.ingestion.federalregister.FederalRegisterDocument;
import mygrant.ingestion.federalregister.FederalRegisterImportRequest;
import mygrant.ingestion.federalregister.FederalRegisterIngestionService;
import mygrant.ingestion.newsrss.NewsRssConnector;
import mygrant.notifications.NotificationStreamRegistry;
import mygrant.testclock.TestClock;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

/**
 * CS203-136: the real proposal (2025-16554) and final rule (2026-14439) end to end, then a synthetic
 * delay notice and a synthetic news report of an injunction, through the Federal Register and news
 * connectors, the alerts, email, the live stream, reassessment and the timeline endpoint. Also the
 * real 2023 H-1B proposal and the two final rules DHS issued on it, the second of which is a
 * separate final rule, not an effective-date move. The real 2020 H-1B registration rulemaking,
 * whose delay notice gives its new date only in its DATES text. And the real court order
 * postponing the D/S rule, which an admin records through the ingestion endpoint because no
 * Federal Register document reports it.
 *
 * <p>Not @Transactional, because alerts and reassessment run after commit; every row it creates is
 * removed afterwards. It needs a database where these documents do not exist yet, as in CI, and is
 * skipped otherwise rather than touching a developer's own copies.
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class RuleLifecycleIntegrationTest {

    private static final List<String> DOCUMENTS = List.of(PROPOSAL, FINAL_RULE, DATE_MOVE, H1B_PROPOSAL,
            H1B_REGISTRATION_RULE, H1B_FINAL_RULE, COURT_ORDER, AC61_PROPOSAL, AC61_FINAL_RULE, AC61_DELAY,
            AC61_WITHDRAWAL);
    private static final List<String> FIXED_TIME_DOCKETS = List.of("DHS Docket No. ICEB-2025-0001");
    private static final List<String> FIXED_TIME_RINS = List.of("1653-AA95");
    private static final String CHANGE_PREFIX = "Rule update for F-1, J-1: ";

    @Autowired FederalRegisterIngestionService federalRegister;
    @Autowired NewsRssConnector news;
    @Autowired PolicyRuleStore store;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @Autowired MockMvc mvc;
    @Autowired TestClock clock;
    @Autowired ImmediateAlertQueue immediateEmails;
    @Autowired DailyDigestService digests;
    @Autowired ApplicationEvents events;
    @Autowired ObjectMapper json;
    @MockBean VectorStore vectors;
    @MockBean FederalRegisterClient client;
    @MockBean ProfileImpactAssessmentService assessments;
    @SpyBean NotificationStreamRegistry streams;

    private final List<Long> createdUsers = new ArrayList<>();
    private final List<String> assessmentThreads = new CopyOnWriteArrayList<>();
    private User student;
    private User reader;

    @BeforeEach
    void requireUnusedFixtures() {
        long existing = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM policy_documents WHERE external_id IN (:ids)
                           OR (source_type = 'SECONDARY' AND title IN (:titles)))
                     + (SELECT count(*) FROM policy_document_identifiers WHERE value IN (:identifiers))
                """, Map.of("ids", DOCUMENTS, "titles", RuleFixtures.NEWS_HEADLINES,
                        "identifiers", RuleFixtures.IDENTIFIERS), Long.class);
        Assumptions.assumeTrue(existing == 0, "This database already holds the CS203-86 fixture documents; "
                + "run against an empty database, as CI does");
        stubFederalRegister(PROPOSAL, RuleFixtures.TITLE, "Proposed Rule", "Notice of proposed rulemaking (NPRM).",
                RuleFixtures.PROPOSAL_PUBLISHED, null, "federal-register/" + PROPOSAL + ".txt",
                FIXED_TIME_DOCKETS, FIXED_TIME_RINS);
        stubFederalRegister(FINAL_RULE, RuleFixtures.TITLE, "Rule", "Final rule.", RuleFixtures.FINAL_PUBLISHED,
                RuleFixtures.FINAL_EFFECTIVE, "federal-register/" + FINAL_RULE + ".txt", FIXED_TIME_DOCKETS,
                FIXED_TIME_RINS);
        // As for a real delay notice, effective_on is not the new date: that is only in DATES.
        stubFederalRegister(DATE_MOVE, RuleFixtures.TITLE + "; Delay of Effective Date", "Rule",
                "Final rule; delay of effective date.", RuleFixtures.DATE_MOVE_PUBLISHED, RuleFixtures.DATE_MOVE_PUBLISHED,
                "federal-register/synthetic-2026-99950-delay.txt", FIXED_TIME_DOCKETS, FIXED_TIME_RINS);
        // The real Federal Register API metadata of the three H-1B documents.
        stubFederalRegister(H1B_PROPOSAL, RuleFixtures.H1B_TITLE, "Proposed Rule", "Notice of proposed rulemaking.",
                RuleFixtures.H1B_PROPOSAL_PUBLISHED, null, "federal-register/" + H1B_PROPOSAL + ".txt",
                List.of("CIS No. 2745-23", "DHS Docket No. USCIS-2023-0005"), List.of("1615-AC70"));
        stubFederalRegister(H1B_REGISTRATION_RULE, RuleFixtures.H1B_REGISTRATION_TITLE, "Rule", "Final rulemaking.",
                RuleFixtures.H1B_REGISTRATION_PUBLISHED, RuleFixtures.H1B_REGISTRATION_EFFECTIVE,
                "federal-register/" + H1B_REGISTRATION_RULE + ".txt",
                List.of("CIS No. 2766-24", "DHS Docket No. USCIS-2023-0005"), List.of("1615-AC70"));
        stubFederalRegister(H1B_FINAL_RULE, RuleFixtures.H1B_TITLE, "Rule", "Final rule.",
                RuleFixtures.H1B_FINAL_PUBLISHED, RuleFixtures.H1B_FINAL_EFFECTIVE,
                "federal-register/" + H1B_FINAL_RULE + ".txt",
                List.of("CIS No. 2766-24", "DHS Docket No. USCIS-2023-0005"), List.of("1615-AC70"));
        // The real Federal Register API metadata of RIN 1615-AC61, as returned: the delay notice's
        // effective_on is 2021-01-08 and its docket has a space; the withdrawal has no docket.
        stubFederalRegister(AC61_PROPOSAL, AC61_TITLE, "Proposed Rule", "Notice of proposed rulemaking.",
                LocalDate.of(2020, 11, 2), null, "federal-register/" + AC61_PROPOSAL + ".txt",
                List.of("CIS No. 2674-20", "DHS Docket No. USCIS-2020-0019"), List.of("1615-AC61"));
        stubFederalRegister(AC61_FINAL_RULE, AC61_TITLE, "Rule", "Final rule.", LocalDate.of(2021, 1, 8),
                LocalDate.of(2021, 3, 9), "federal-register/" + AC61_FINAL_RULE + ".txt",
                List.of("CIS No. 2679-21", "DHS Docket No. USCIS-2020-0019"), List.of("1615-AC61"));
        stubFederalRegister(AC61_DELAY, RuleFixtures.AC61_DELAY_TITLE, "Rule",
                "Final rule; delay of effective date; request for comments.", LocalDate.of(2021, 2, 8),
                LocalDate.of(2021, 1, 8), "federal-register/" + AC61_DELAY + ".txt",
                List.of("CIS No. 2680-21", "Docket No: USCIS 2020-0019"), List.of("1615-AC61"));
        stubFederalRegister(AC61_WITHDRAWAL, RuleFixtures.AC61_WITHDRAWAL_TITLE, "Rule", "Final rule; withdrawal.",
                LocalDate.of(2021, 12, 22), null, "federal-register/" + AC61_WITHDRAWAL + ".txt", List.of(),
                List.of("1615-AC61"));
        when(assessments.assess(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            assessmentThreads.add(Thread.currentThread().getName());
            return new ProfileImpactAssessment(ImpactUrgency.MEDIUM, 80, "Your admission period becomes fixed.",
                    List.of("You hold F-1 status."), List.of("Check the end date on your I-94"), List.of(), false,
                    "test", "test", new JudgeEvaluation(JudgeEvaluation.Verdict.YES, "Supported", "judge"), 1);
        });
        student = applicant("F-1");
        reader = applicant("J-1");
    }

    @AfterEach
    void removeCreatedRows() {
        List<Long> rules = jdbc.queryForList("""
                SELECT DISTINCT d.rule_id FROM policy_rule_documents d JOIN policy_documents p ON p.id = d.policy_id
                WHERE p.external_id IN (:ids) OR (p.source_type = 'SECONDARY' AND p.title IN (:titles))
                """, Map.of("ids", DOCUMENTS, "titles", RuleFixtures.NEWS_HEADLINES), Long.class);
        jdbc.update("""
                DELETE FROM policy_documents WHERE external_id IN (:ids)
                   OR (source_type = 'SECONDARY' AND title IN (:titles))
                """, Map.of("ids", DOCUMENTS, "titles", RuleFixtures.NEWS_HEADLINES));
        if (!rules.isEmpty()) {
            jdbc.update("DELETE FROM policy_rules WHERE id IN (:ids)", Map.of("ids", rules));
        }
        for (Long id : createdUsers) {
            clock.setUserDate(id, null);
            jdbc.update("DELETE FROM account_deletion_audit WHERE user_id = :id", Map.of("id", id));
            jdbc.update("DELETE FROM users WHERE id = :id", Map.of("id", id));
        }
    }

    @Test
    void theProposedToFinalLifecycleAlertsAndReassessesOncePerChange() throws Exception {
        // The proposal starts the rule and gets the usual new-policy alert.
        long proposal = importDocument(PROPOSAL);
        assertThat(messages(student)).containsExactly(
                "New policy affecting F-1, J-1: " + RuleFixtures.TITLE);
        // The reader asked how the proposal affects them, so the final rule makes that advice stale.
        assessedEarlier(reader, proposal);

        long finalRule = importDocument(FINAL_RULE);

        long ruleId = store.findLink(finalRule).orElseThrow().ruleId();
        assertThat(store.findLink(proposal).orElseThrow().ruleId()).isEqualTo(ruleId);
        assertThat(events.stream(PolicyChangedEvent.class)).singleElement()
                .satisfies(change -> assertThat(change.source().externalId()).isEqualTo(FINAL_RULE));
        String finalAlert = CHANGE_PREFIX + "moved from proposed to final, effective 15 Sep 2026: " + RuleFixtures.TITLE;
        assertThat(alertsFor(student, finalRule)).containsExactly(finalAlert);
        assertThat(alertsFor(reader, finalRule)).containsExactly(finalAlert);
        verify(streams, timeout(5_000).times(2)).publish(eq(student.getId()), any());

        // Exactly one reassessment for the change: the reader, on the final rule, in the background.
        verify(assessments, timeout(10_000)).assess(eq(reader.getEmail()), document(FINAL_RULE), any(), any(), any());
        verify(assessments, after(500).times(1)).assess(any(), any(), any(), any(), any());
        assertThat(assessmentThreads).singleElement().isNotEqualTo(Thread.currentThread().getName());
        // Through the gated store: the judge approved it, so it is published to the reader.
        await(() -> publishedAssessments(reader, finalRule) == 1);
        verify(assessments, never()).assess(eq(student.getEmail()), any(), any(), any(), any());

        // Each alert is emailed at most once, however often the email jobs run.
        Instant now = Instant.now();
        immediateEmails.queueDueFor(student.getId(), now);
        digests.buildDigestFor(student.getId(), digests.today(now), now);
        assertThat(immediateEmails.queueDueFor(student.getId(), now)).isZero();
        assertThat(digests.buildDigestFor(student.getId(), digests.today(now), now)).isEmpty();
        assertThat(emailsCarrying(student, finalRule)).isEqualTo(1);

        // Importing either document again changes nothing.
        int history = store.history(ruleId).size();
        events.clear();
        importDocument(FINAL_RULE);
        importDocument(PROPOSAL);
        assertThat(store.history(ruleId)).hasSize(history);
        assertThat(events.stream(PolicyChangedEvent.class)).isEmpty();
        assertThat(messages(student)).hasSize(2);
        verify(assessments, after(500).times(1)).assess(any(), any(), any(), any(), any());

        // The synthetic delay notice moves the effective date.
        long delay = importDocument(DATE_MOVE);
        assertThat(alertsFor(student, delay)).containsExactly(CHANGE_PREFIX
                + "effective date moved from 15 Sep 2026 to 1 Nov 2026: " + RuleFixtures.TITLE);
        verify(assessments, timeout(10_000)).assess(eq(reader.getEmail()), document(DATE_MOVE), any(), any(), any());

        // Undisputed, the rule takes effect on the moved date.
        clock.setUserDate(student.getId(), LocalDate.of(2026, 11, 2));
        mvc.perform(get("/api/v1/policies/{id}/timeline", finalRule).header("Authorization", token(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStage").value("EFFECTIVE"))
                .andExpect(jsonPath("$.effectiveDatePassedWhileDisputed").value(false))
                .andExpect(jsonPath("$.steps.length()").value(4))
                .andExpect(jsonPath("$.steps[3].changes").value(org.hamcrest.Matchers.contains("TOOK_EFFECT")))
                .andExpect(jsonPath("$.steps[3].derived").value(true))
                .andExpect(jsonPath("$.steps[3].observedOn").value("2026-11-01"))
                .andExpect(jsonPath("$.steps[3].source.externalId").value(DATE_MOVE));

        // The synthetic news report of an injunction marks the rule disputed; its stage stays final.
        news.importSaved(RuleFixtures.text(RuleFixtures.NEWS_FEED));
        long injunction = newsId(RuleFixtures.INJUNCTION_HEADLINE);
        assertThat(alertsFor(student, injunction)).containsExactly(CHANGE_PREFIX
                + "status disputed: litigation or a court stay reported by The Guardian. The rule is final, but its "
                + "effective date of 1 Nov 2026 is in question: " + RuleFixtures.TITLE);
        assertThat(store.findRule(ruleId).orElseThrow().state())
                .isEqualTo(new RuleState(mygrant.policies.PolicyStatus.FINAL, RuleFixtures.MOVED_EFFECTIVE, true));
        assertThat(messages(student)).hasSize(4);
        await(() -> publishedAssessments(reader, injunction) == 1);

        // The timeline, on the student's date before and after the moved effective date.
        clock.setUserDate(student.getId(), LocalDate.of(2026, 10, 15));
        mvc.perform(get("/api/v1/policies/{id}/timeline", finalRule).header("Authorization", token(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleId").value(ruleId))
                .andExpect(jsonPath("$.primaryStage").value("FINAL"))
                .andExpect(jsonPath("$.currentStage").value("FINAL"))
                .andExpect(jsonPath("$.asOf").value("2026-10-15"))
                .andExpect(jsonPath("$.effectiveDate").value("2026-11-01"))
                .andExpect(jsonPath("$.disputed").value(true))
                .andExpect(jsonPath("$.effectiveDatePassedWhileDisputed").value(false))
                .andExpect(jsonPath("$.disputedBy.title").value(RuleFixtures.INJUNCTION_HEADLINE))
                .andExpect(jsonPath("$.disputedBy.sourceType").value("SECONDARY"))
                .andExpect(jsonPath("$.identifiers[*].value").value(org.hamcrest.Matchers.contains("ICEB-2025-0001",
                        "1653-AA95")))
                .andExpect(jsonPath("$.steps.length()").value(4))
                .andExpect(jsonPath("$.steps[0].primaryStage").value("PROPOSED"))
                .andExpect(jsonPath("$.steps[0].previousPrimaryStage").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.steps[0].source.externalId").value(PROPOSAL))
                .andExpect(jsonPath("$.steps[0].source.url").value("https://www.federalregister.gov/d/" + PROPOSAL))
                .andExpect(jsonPath("$.steps[1].primaryStage").value("FINAL"))
                .andExpect(jsonPath("$.steps[1].previousPrimaryStage").value("PROPOSED"))
                .andExpect(jsonPath("$.steps[1].effectiveDate").value("2026-09-15"))
                .andExpect(jsonPath("$.steps[1].changes").value(org.hamcrest.Matchers.contains("STAGE", "EFFECTIVE_DATE")))
                .andExpect(jsonPath("$.steps[1].source.externalId").value(FINAL_RULE))
                .andExpect(jsonPath("$.steps[2].previousEffectiveDate").value("2026-09-15"))
                .andExpect(jsonPath("$.steps[2].effectiveDate").value("2026-11-01"))
                .andExpect(jsonPath("$.steps[2].source.externalId").value(DATE_MOVE))
                .andExpect(jsonPath("$.steps[3].changes").value(org.hamcrest.Matchers.contains("DISPUTED")))
                .andExpect(jsonPath("$.steps[3].primaryStage").value("FINAL"))
                .andExpect(jsonPath("$.steps[3].source.agency").value("The Guardian"));
        clock.setUserDate(student.getId(), LocalDate.of(2026, 11, 2));
        // Any document of the rule, including the news item, finds the same timeline. Disputed, the
        // rule is not shown as in effect once its date has passed.
        mvc.perform(get("/api/v1/policies/{id}/timeline", injunction).header("Authorization", token(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleId").value(ruleId))
                .andExpect(jsonPath("$.primaryStage").value("FINAL"))
                .andExpect(jsonPath("$.currentStage").value("FINAL"))
                .andExpect(jsonPath("$.effectiveDate").value("2026-11-01"))
                .andExpect(jsonPath("$.effectiveDatePassedWhileDisputed").value(true))
                .andExpect(jsonPath("$.steps.length()").value(4))
                .andExpect(jsonPath("$.steps[*].changes[*]").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("TOOK_EFFECT"))));
    }

    @Test
    void aRealDelayNoticeMovesTheDateItsDatesTextGivesAndTheRuleIsThenWithdrawn() throws Exception {
        User worker = applicant("H-1B");
        long proposal = importDocument(AC61_PROPOSAL);
        assertThat(alertsFor(worker, proposal)).containsExactly("New policy affecting H-1B: " + AC61_TITLE);
        long finalRule = importDocument(AC61_FINAL_RULE);
        long delay = importDocument(AC61_DELAY);
        long withdrawal = importDocument(AC61_WITHDRAWAL);
        long ruleId = store.findLink(finalRule).orElseThrow().ruleId();

        assertThat(List.of(proposal, delay, withdrawal))
                .allSatisfy(id -> assertThat(store.findLink(id).orElseThrow().ruleId()).isEqualTo(ruleId));
        // The delay notice's "Docket No: USCIS 2020-0019" is the rulemaking's docket; the withdrawal has only the RIN.
        assertThat(store.findLink(delay).orElseThrow().method()).isEqualTo(MatchMethod.DOCKET_AND_RIN);
        assertThat(store.findLink(withdrawal).orElseThrow().method()).isEqualTo(MatchMethod.RIN);
        assertThat(store.documents(ruleId)).noneMatch(document -> document.link().needsReview());
        assertThat(store.findRule(ruleId).orElseThrow().state()).isEqualTo(
                new RuleState(mygrant.policies.PolicyStatus.WITHDRAWN, LocalDate.of(2021, 12, 31), false));

        // One change alert each for the final rule, the delay and the withdrawal.
        String prefix = "Rule update for H-1B: ";
        assertThat(alertsFor(worker, finalRule)).containsExactly(
                prefix + "moved from proposed to final, effective 9 Mar 2021: " + AC61_TITLE);
        assertThat(alertsFor(worker, delay)).containsExactly(
                prefix + "effective date moved from 9 Mar 2021 to 31 Dec 2021: " + AC61_TITLE);
        assertThat(alertsFor(worker, withdrawal)).containsExactly(
                prefix + "moved from final to withdrawn: " + AC61_TITLE);
        assertThat(messages(worker)).hasSize(4).noneMatch(message -> message.contains("8 Jan 2021"));
        assertThat(events.stream(PolicyChangedEvent.class)).extracting(change -> change.source().externalId())
                .containsExactly(AC61_FINAL_RULE, AC61_DELAY, AC61_WITHDRAWAL);

        clock.setUserDate(worker.getId(), LocalDate.of(2026, 11, 6));
        mvc.perform(get("/api/v1/policies/{id}/timeline", delay).header("Authorization", token(worker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primaryStage").value("WITHDRAWN"))
                .andExpect(jsonPath("$.currentStage").value("WITHDRAWN"))
                .andExpect(jsonPath("$.effectiveDate").value("2021-12-31"))
                .andExpect(jsonPath("$.identifiers[*].value").value(org.hamcrest.Matchers.contains("USCIS-2020-0019",
                        "1615-AC61")))
                .andExpect(jsonPath("$.steps.length()").value(4))
                .andExpect(jsonPath("$.steps[0].primaryStage").value("PROPOSED"))
                .andExpect(jsonPath("$.steps[1].changes").value(org.hamcrest.Matchers.contains("STAGE", "EFFECTIVE_DATE")))
                .andExpect(jsonPath("$.steps[1].effectiveDate").value("2021-03-09"))
                .andExpect(jsonPath("$.steps[2].changes").value(org.hamcrest.Matchers.contains("EFFECTIVE_DATE")))
                .andExpect(jsonPath("$.steps[2].previousEffectiveDate").value("2021-03-09"))
                .andExpect(jsonPath("$.steps[2].effectiveDate").value("2021-12-31"))
                .andExpect(jsonPath("$.steps[2].source.externalId").value(AC61_DELAY))
                .andExpect(jsonPath("$.steps[3].changes").value(org.hamcrest.Matchers.contains("STAGE")))
                .andExpect(jsonPath("$.steps[3].previousPrimaryStage").value("FINAL"))
                .andExpect(jsonPath("$.steps[3].primaryStage").value("WITHDRAWN"))
                .andExpect(jsonPath("$.steps[3].source.externalId").value(AC61_WITHDRAWAL));
    }

    @Test
    void aCourtOrderAnAdminRecordsMarksTheRuleDisputedAndNotInEffect() throws Exception {
        importDocument(PROPOSAL);
        long finalRule = importDocument(FINAL_RULE);
        long ruleId = store.findLink(finalRule).orElseThrow().ruleId();
        User admin = user(null, UserRole.ADMIN);
        events.clear();

        // No Federal Register document records the order, and the news about it carries no link, so
        // an admin posts it as a secondary source that links the final rule: the README's request body.
        String body = RuleFixtures.text(RuleFixtures.COURT_ORDER_REQUEST);
        assertThat(json.readTree(body).path("sourceReferences").get(0).asText())
                .isEqualTo("https://www.federalregister.gov/d/" + FINAL_RULE);
        String created = mvc.perform(post("/api/v1/ingestion/documents").header("Authorization", token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alreadyIndexed").value(false))
                .andReturn().getResponse().getContentAsString();
        long courtOrder = json.readTree(created).path("policyId").asLong();

        var link = store.findLink(courtOrder).orElseThrow();
        assertThat(link.ruleId()).isEqualTo(ruleId);
        assertThat(link.method()).isEqualTo(MatchMethod.FR_DOCUMENT);
        assertThat(link.confidence()).isEqualTo(mygrant.common.Confidence.HIGH);
        assertThat(link.needsReview()).isFalse();
        assertThat(store.findRule(ruleId).orElseThrow().state()).isEqualTo(
                new RuleState(mygrant.policies.PolicyStatus.FINAL, RuleFixtures.FINAL_EFFECTIVE, true));
        assertThat(events.stream(PolicyChangedEvent.class)).singleElement().satisfies(change -> {
            assertThat(change.changes()).containsExactly(RuleChange.DISPUTED);
            assertThat(change.source().externalId()).isEqualTo(COURT_ORDER);
        });

        // One disputed alert for each matching applicant, saying the effective date is in question.
        String disputedAlert = CHANGE_PREFIX + "status disputed: litigation or a court stay reported by "
                + RuleFixtures.COURT + ". The rule is final, but its effective date of 15 Sep 2026 is in question: "
                + RuleFixtures.TITLE;
        assertThat(alertsFor(student, courtOrder)).containsExactly(disputedAlert);
        assertThat(alertsFor(reader, courtOrder)).containsExactly(disputedAlert);
        assertThat(messages(student)).hasSize(3);

        // Posting it again changes nothing.
        mvc.perform(post("/api/v1/ingestion/documents").header("Authorization", token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alreadyIndexed").value(true));
        assertThat(messages(student)).hasSize(3);

        clock.setUserDate(student.getId(), LocalDate.of(2026, 11, 6));
        mvc.perform(get("/api/v1/policies/{id}/timeline", finalRule).header("Authorization", token(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value("2026-11-06"))
                .andExpect(jsonPath("$.primaryStage").value("FINAL"))
                .andExpect(jsonPath("$.currentStage").value("FINAL"))
                .andExpect(jsonPath("$.effectiveDate").value("2026-09-15"))
                .andExpect(jsonPath("$.disputed").value(true))
                .andExpect(jsonPath("$.effectiveDatePassedWhileDisputed").value(true))
                .andExpect(jsonPath("$.disputedBy.externalId").value(COURT_ORDER))
                .andExpect(jsonPath("$.disputedBy.title").value(RuleFixtures.COURT_ORDER_TITLE))
                .andExpect(jsonPath("$.disputedBy.agency").value(RuleFixtures.COURT))
                .andExpect(jsonPath("$.disputedBy.url").value(RuleFixtures.COURT_ORDER_URL))
                .andExpect(jsonPath("$.disputedBy.publishedOn").value(RuleFixtures.COURT_ORDER_PUBLISHED.toString()))
                .andExpect(jsonPath("$.documents[2].matchMethod").value("FR_DOCUMENT"))
                .andExpect(jsonPath("$.documents[2].confidence").value("HIGH"))
                .andExpect(jsonPath("$.steps.length()").value(3))
                .andExpect(jsonPath("$.steps[2].changes").value(org.hamcrest.Matchers.contains("DISPUTED")))
                .andExpect(jsonPath("$.steps[2].primaryStage").value("FINAL"))
                .andExpect(jsonPath("$.steps[*].changes[*]").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("TOOK_EFFECT"))));
    }

    @Test
    void aSecondFinalRuleInTheSameRulemakingIsItsOwnStepNotADateMove() throws Exception {
        User worker = applicant("H-1B");
        long proposal = importDocument(H1B_PROPOSAL);
        assertThat(alertsFor(worker, proposal)).containsExactly("New policy affecting F-1, H-1B: " + RuleFixtures.H1B_TITLE);
        long registration = importDocument(H1B_REGISTRATION_RULE);
        long ruleId = store.findLink(registration).orElseThrow().ruleId();

        // The first final rule moves the proposal to final, with one change alert.
        assertThat(events.stream(PolicyChangedEvent.class)).singleElement().satisfies(change -> {
            assertThat(change.source().externalId()).isEqualTo(H1B_REGISTRATION_RULE);
            assertThat(change.changes()).containsExactlyInAnyOrder(RuleChange.STAGE, RuleChange.EFFECTIVE_DATE);
        });
        assertThat(alertsFor(worker, registration)).containsExactly(
                "Rule update for H-1B: moved from proposed to final, effective 4 Mar 2024: " + RuleFixtures.H1B_TITLE);
        events.clear();

        // The second final rule joins the same rule as its own step: no date move, no change event.
        long secondFinal = importDocument(H1B_FINAL_RULE);

        assertThat(store.findLink(secondFinal).orElseThrow().ruleId()).isEqualTo(ruleId);
        assertThat(store.findLink(proposal).orElseThrow().ruleId()).isEqualTo(ruleId);
        assertThat(events.stream(PolicyChangedEvent.class)).isEmpty();
        assertThat(store.findRule(ruleId).orElseThrow().state()).isEqualTo(
                new RuleState(mygrant.policies.PolicyStatus.FINAL, RuleFixtures.H1B_REGISTRATION_EFFECTIVE, false));
        assertThat(alertsFor(worker, secondFinal)).containsExactly(
                "New policy affecting F-1, H-1B: " + RuleFixtures.H1B_TITLE + " (effective 2025-01-17)");
        assertThat(messages(worker)).hasSize(3).noneMatch(message -> message.contains("effective date moved"));

        clock.setUserDate(worker.getId(), LocalDate.of(2026, 11, 6));
        mvc.perform(get("/api/v1/policies/{id}/timeline", secondFinal).header("Authorization", token(worker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleId").value(ruleId))
                .andExpect(jsonPath("$.primaryStage").value("FINAL"))
                .andExpect(jsonPath("$.currentStage").value("EFFECTIVE"))
                .andExpect(jsonPath("$.effectiveDate").value("2024-03-04"))
                .andExpect(jsonPath("$.identifiers[*].value").value(org.hamcrest.Matchers.contains("USCIS-2023-0005",
                        "1615-AC70")))
                .andExpect(jsonPath("$.documents[*].externalId").value(org.hamcrest.Matchers.contains(H1B_PROPOSAL,
                        H1B_REGISTRATION_RULE, H1B_FINAL_RULE)))
                .andExpect(jsonPath("$.steps.length()").value(4))
                .andExpect(jsonPath("$.steps[0].primaryStage").value("PROPOSED"))
                .andExpect(jsonPath("$.steps[0].source.externalId").value(H1B_PROPOSAL))
                .andExpect(jsonPath("$.steps[1].primaryStage").value("FINAL"))
                .andExpect(jsonPath("$.steps[1].changes").value(org.hamcrest.Matchers.contains("STAGE", "EFFECTIVE_DATE")))
                .andExpect(jsonPath("$.steps[1].effectiveDate").value("2024-03-04"))
                .andExpect(jsonPath("$.steps[1].source.externalId").value(H1B_REGISTRATION_RULE))
                .andExpect(jsonPath("$.steps[2].changes").value(org.hamcrest.Matchers.contains("TOOK_EFFECT")))
                .andExpect(jsonPath("$.steps[2].observedOn").value("2024-03-04"))
                .andExpect(jsonPath("$.steps[3].changes").value(org.hamcrest.Matchers.contains("ADDITIONAL_FINAL_RULE")))
                .andExpect(jsonPath("$.steps[3].primaryStage").value("FINAL"))
                .andExpect(jsonPath("$.steps[3].previousPrimaryStage").value("FINAL"))
                .andExpect(jsonPath("$.steps[3].effectiveDate").value("2024-03-04"))
                .andExpect(jsonPath("$.steps[3].previousEffectiveDate").value("2024-03-04"))
                .andExpect(jsonPath("$.steps[3].ownEffectiveDate").value("2025-01-17"))
                .andExpect(jsonPath("$.steps[3].source.externalId").value(H1B_FINAL_RULE));
    }

    @Test
    void theTimelineReportsMissingPoliciesAndPoliciesInNoRule() throws Exception {
        news.importSaved(RuleFixtures.text(RuleFixtures.NEWS_FEED));
        long unlinked = newsId("Universities sue over F-1 student admission limits");
        String token = token(student);

        mvc.perform(get("/api/v1/policies/{id}/timeline", unlinked).header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Policy document " + unlinked + " is not part of a tracked rule"));
        mvc.perform(get("/api/v1/policies/{id}/timeline", Long.MAX_VALUE).header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Policy document " + Long.MAX_VALUE + " was not found"));
        mvc.perform(get("/api/v1/policies/abc/timeline").header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/policies/{id}/timeline", unlinked)).andExpect(status().isUnauthorized());
    }

    /** Waits up to ten seconds for work on the background thread to land. */
    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("background work finished in time").isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private long importDocument(String documentNumber) {
        return federalRegister.ingest(new FederalRegisterImportRequest(documentNumber, "admin@example.com", false))
                .ingestion().policyId();
    }

    private void stubFederalRegister(String number, String title, String type, String action, LocalDate published,
            LocalDate effective, String resource, List<String> dockets, List<String> rins) {
        var document = new FederalRegisterDocument(number, title, type, published, effective,
                "https://www.federalregister.gov/d/" + number, "https://example.test/" + number + ".txt",
                List.of(new FederalRegisterDocument.Agency("Homeland Security Department", "homeland-security-department")),
                action, dockets, rins);
        when(client.getDocument(number)).thenReturn(document);
        when(client.downloadFullText(document.rawTextUrl())).thenReturn(RuleFixtures.text(resource));
    }

    private User applicant(String visaType) {
        return user(visaType, UserRole.APPLICANT);
    }

    private User user(String visaType, UserRole role) {
        String suffix = UUID.randomUUID().toString();
        User user = new User("Lifecycle test", "rule-lifecycle-" + suffix + "@example.com", null, visaType, null, null);
        user.setRole(role);
        user.setWorkosUserId("user_rule_lifecycle_" + suffix);
        user.setProfileComplete(true);
        user = users.saveAndFlush(user);
        createdUsers.add(user.getId());
        return user;
    }

    private void assessedEarlier(User user, long policyId) {
        jdbc.update("""
                INSERT INTO policy_recommendations (user_id, policy_id, input_hash, assessment_json)
                VALUES (:user, :policy, :hash, '{}')
                """, Map.of("user", user.getId(), "policy", policyId, "hash", UUID.randomUUID().toString()));
    }

    private List<String> messages(User user) {
        return jdbc.queryForList("SELECT message FROM notifications WHERE user_id = :id ORDER BY id",
                Map.of("id", user.getId()), String.class);
    }

    private List<String> alertsFor(User user, long policyId) {
        return jdbc.queryForList("SELECT message FROM notifications WHERE user_id = :user AND policy_id = :policy",
                Map.of("user", user.getId(), "policy", policyId), String.class);
    }

    private int publishedAssessments(User user, long policyId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM policy_recommendations
                WHERE user_id = :user AND policy_id = :policy AND publication_status = 'PUBLISHED'
                """, Map.of("user", user.getId(), "policy", policyId), Integer.class);
    }

    private int emailsCarrying(User user, long policyId) {
        return jdbc.queryForObject("""
                SELECT count(DISTINCT email_delivery_id) FROM notifications
                WHERE user_id = :user AND policy_id = :policy AND email_delivery_id IS NOT NULL
                """, Map.of("user", user.getId(), "policy", policyId), Integer.class);
    }

    private long newsId(String title) {
        return jdbc.queryForObject("SELECT id FROM policy_documents WHERE title = :title AND source_type = 'SECONDARY'",
                Map.of("title", title), Long.class);
    }

    private String token(User user) {
        return "Bearer " + jwt.generateAccessToken(user);
    }

    private static FederalRegisterDocument document(String documentNumber) {
        return argThat(document -> document != null && documentNumber.equals(document.documentNumber()));
    }
}
