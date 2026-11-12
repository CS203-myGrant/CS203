package mygrant.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import mygrant.ai.OpenAiGenerationService;
import mygrant.auth.JwtService;
import mygrant.ingestion.PolicyIngestionService;
import mygrant.ingestion.dto.IngestPolicyRequest;
import mygrant.policies.PolicyDocument;
import mygrant.policies.PolicyDocumentRepository;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;
import mygrant.support.PgvectorIntegrationTestBase;
import mygrant.user.ProfileFieldConsent;
import mygrant.user.ProfileFieldConsentRepository;
import mygrant.user.ProfileFieldDefinition;
import mygrant.user.User;
import mygrant.user.UserProfile;
import mygrant.user.UserProfileRepository;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

/**
 * CS203-153: one ingestion gives every affected user their own report, published to their feed
 * and checklist or held in the reviewer queue by the shared review policy. Real commits, the real
 * async worker and the real HTTP API; the model, judge and embeddings are mocked.
 */
@SpringBootTest(properties = {
        "mygrant.impact.assessments-enabled=true",
        "mygrant.impact.review-confidence-threshold=60",
        "jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@AutoConfigureMockMvc
class AutoPublishIntegrationTest extends PgvectorIntegrationTestBase {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired PolicyIngestionService ingestion;
    @Autowired IngestionAssessmentService worker;
    @Autowired ImpactAssessmentRepository results;
    @Autowired UserRepository users;
    @Autowired UserProfileRepository profiles;
    @Autowired ProfileFieldConsentRepository consents;
    @Autowired PolicyDocumentRepository policies;
    @MockBean OpenAiGenerationService generator;
    @MockBean ImpactJudgeService judge;
    @MockBean VectorStore vectors;

    private final List<Long> createdUsers = new ArrayList<>();
    private final List<Long> createdPolicies = new ArrayList<>();

    @BeforeEach
    void providers() {
        when(judge.evaluate(anyString(), any())).thenReturn(
                new JudgeEvaluation(JudgeEvaluation.Verdict.YES, "Supported", "test-judge"));
        // Each profile's expiry date reaches the prompt, so the two applicants get different results.
        when(generator.generateJson(anyString(), anyString(), any(), anyString())).thenAnswer(call -> {
            String prompt = call.getArgument(1);
            return prompt.contains("2026-12-01")
                    ? candidate("HIGH", 92, false, "Your visa expires while the rule applies")
                    : candidate("LOW", 85, false, "Your later expiry date is not affected");
        });
    }

    @AfterEach
    void cleanUp() {
        createdPolicies.forEach(policies::deleteById);
        createdUsers.forEach(id -> users.deleteById(id));
    }

    @Test
    void oneIngestionGivesEachAffectedUserTheirOwnPublishedReportAndChecklist() throws Exception {
        User early = applicant("2026-12-01");
        User late = applicant("2028-12-01");
        User unaffected = applicant("2027-01-01", "J-1");
        long policyId = ingest(SourceType.PRIMARY);
        awaitLinked(policyId, 2);

        JsonNode earlyFeed = feed(early);
        JsonNode lateFeed = feed(late);
        assertThat(earlyFeed.path("totalElements").asLong()).isOne();
        assertThat(lateFeed.path("totalElements").asLong()).isOne();
        assertThat(earlyFeed.at("/content/0/summary").asText()).isEqualTo("Your visa expires while the rule applies");
        assertThat(earlyFeed.at("/content/0/severity").asText()).isEqualTo("HIGH");
        assertThat(lateFeed.at("/content/0/summary").asText()).isEqualTo("Your later expiry date is not affected");
        assertThat(lateFeed.at("/content/0/severity").asText()).isEqualTo("LOW");
        assertThat(feed(unaffected).path("totalElements").asLong()).isZero();
        // Each processing record points at the feed item it was published as.
        assertThat(processingRow(early, policyId).getRecommendationId())
                .isEqualTo(earlyFeed.at("/content/0/id").asLong());
        assertThat(json(get("/api/v1/actions"), early).path("content")).extracting(item -> item.path("title").asText())
                .containsExactly("Check the official rule");
    }

    @Test
    void confidentVerifiedHighResultIsPublishedWithoutReview() throws Exception {
        User owner = applicant("2026-12-01");
        User manager = managerOf(owner);
        long policyId = ingest(SourceType.PRIMARY);
        awaitLinked(policyId, 1);

        assertThat(feed(owner).at("/content/0/severity").asText()).isEqualTo("HIGH");
        assertThat(processingRow(owner, policyId).getReviewStatus()).isEqualTo(AssessmentReviewStatus.NOT_REQUIRED);
        assertThat(queue(manager)).isEmpty();
    }

    @Test
    void lowConfidenceResultWaitsInTheQueueAndApprovingItPublishesIt() throws Exception {
        when(generator.generateJson(anyString(), anyString(), any(), anyString()))
                .thenReturn(candidate("MEDIUM", 40, false, "Possibly affected"));
        User owner = applicant("2026-12-01");
        User manager = managerOf(owner);
        long policyId = ingest(SourceType.PRIMARY);
        awaitLinked(policyId, 1);

        assertThat(feed(owner).path("totalElements").asLong()).isZero();
        assertThat(json(get("/api/v1/actions"), owner).path("totalElements").asLong()).isZero();
        List<JsonNode> queued = queue(manager);
        assertThat(queued).hasSize(1);
        assertThat(queued.getFirst().path("draftSummary").asText()).isEqualTo("Possibly affected");
        assertThat(queued.getFirst().path("holdReasons")).extracting(JsonNode::asText)
                .containsExactly("Low confidence (40%)");
        // The policy page tells the owner it is being checked, without calling the model again.
        clearInvocations(generator);
        JsonNode page = json(post("/api/v1/policies/" + policyId + "/recommendation"), owner);
        assertThat(page.path("status").asText()).isEqualTo("AWAITING_REVIEW");
        assertThat(page.path("summary").asText()).doesNotContain("Possibly affected");
        verify(generator, never()).generateJson(anyString(), anyString(), any(), anyString());

        long id = queued.getFirst().path("id").asLong();
        mvc.perform(patch("/api/v1/review/impacts/" + id).header("Authorization", token(manager))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"severity":"MEDIUM","confidence":40,"summary":"Possibly affected",
                                 "analysis":["Visa expiry timing"],"missingInformation":[],
                                 "actionItems":["Check the official rule"]}
                                """))
                .andExpect(status().isOk());

        assertThat(feed(owner).at("/content/0/summary").asText()).isEqualTo("Possibly affected");
        assertThat(json(get("/api/v1/actions"), owner).path("totalElements").asLong()).isOne();
        assertThat(queue(manager)).isEmpty();
        assertThat(processingRow(owner, policyId).getReviewStatus()).isEqualTo(AssessmentReviewStatus.APPROVED);
    }

    @Test
    void unverifiedResultIsHeldEvenWhenConfident() throws Exception {
        when(judge.evaluate(anyString(), any())).thenReturn(
                new JudgeEvaluation(JudgeEvaluation.Verdict.UNAVAILABLE, "Judge down", "test-judge"));
        User owner = applicant("2026-12-01");
        User manager = managerOf(owner);
        long policyId = ingest(SourceType.PRIMARY);
        awaitLinked(policyId, 1);

        assertThat(feed(owner).path("totalElements").asLong()).isZero();
        assertThat(queue(manager)).singleElement().satisfies(item -> assertThat(item.path("holdReasons"))
                .extracting(JsonNode::asText).containsExactly("Independent check did not pass"));
    }

    @Test
    void openingThePolicyPageAfterIngestionReusesTheResultUntilTheProfileChanges() throws Exception {
        User owner = applicant("2026-12-01");
        long policyId = ingest(SourceType.PRIMARY);
        awaitLinked(policyId, 1);
        clearInvocations(generator);

        JsonNode page = json(post("/api/v1/policies/" + policyId + "/recommendation"), owner);
        assertThat(page.path("summary").asText()).isEqualTo("Your visa expires while the rule applies");
        assertThat(page.path("urgency").asText()).isEqualTo("HIGH");
        verify(generator, never()).generateJson(anyString(), anyString(), any(), anyString());

        // A new profile version is a different input, so the page assesses it afresh.
        UserProfile profile = profiles.findById(owner.getId()).orElseThrow();
        profile.setVisaExpiryDate(LocalDate.parse("2028-12-01"));
        profile.setVersion(profile.getVersion() + 1);
        profiles.saveAndFlush(profile);
        json(post("/api/v1/policies/" + policyId + "/recommendation"), owner);
        verify(generator, times(1)).generateJson(anyString(), anyString(), any(), anyString());
    }

    @Test
    void humanReviewedRowsAreNeverChangedByBackgroundOrPageAssessments() throws Exception {
        when(generator.generateJson(anyString(), anyString(), any(), anyString()))
                .thenReturn(candidate("MEDIUM", 40, false, "Possibly affected"));
        User owner = applicant("2026-12-01");
        User manager = managerOf(owner);
        long policyId = ingest(SourceType.PRIMARY);
        awaitLinked(policyId, 1);
        long id = queue(manager).getFirst().path("id").asLong();
        mvc.perform(patch("/api/v1/review/impacts/" + id).header("Authorization", token(manager))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"severity":"LOW","confidence":95,"summary":"Human correction",
                                 "analysis":[],"missingInformation":[],"actionItems":["Read the source"]}
                                """))
                .andExpect(status().isOk());
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM policy_recommendations WHERE id = ?", id);
        clearInvocations(generator);

        // The background worker runs again for this profile version, and the owner asks for a fresh result.
        jdbc.update("DELETE FROM impact_assessments WHERE policy_id = ?", policyId);
        worker.assessMatchingProfiles(policyId);
        JsonNode page = json(post("/api/v1/policies/" + policyId + "/recommendation?refresh=true"), owner);

        assertThat(jdbc.queryForMap("SELECT * FROM policy_recommendations WHERE id = ?", id)).isEqualTo(before);
        assertThat(page.path("summary").asText()).isEqualTo("Human correction");
        assertThat(processingRow(owner, policyId).getRecommendationId()).isEqualTo(id);
        assertThat(processingRow(owner, policyId).getReviewStatus()).isEqualTo(AssessmentReviewStatus.APPROVED);
        verify(generator, never()).generateJson(anyString(), anyString(), any(), anyString());
    }

    @Test
    void backgroundAssessmentReusesAResultTheOwnerAlreadyHas() throws Exception {
        User owner = applicant("2026-12-01");
        // Saved without its ingestion event, so the owner opens the page before any background work.
        long policyId = policies.saveAndFlush(new PolicyDocument("auto-" + UUID.randomUUID(), "Student expiry rule",
                "DHS", "F-1", PolicyStatus.FINAL, SourceType.PRIMARY, LocalDate.now(), null,
                "https://example.org/rule", "Students must check their visa expiry dates.", "0".repeat(64))).getId();
        createdPolicies.add(policyId);
        long shown = json(post("/api/v1/policies/" + policyId + "/recommendation"), owner).path("confidence").asLong();
        assertThat(shown).isEqualTo(92);
        clearInvocations(generator);

        worker.assessMatchingProfiles(policyId);

        verify(generator, never()).generateJson(anyString(), anyString(), any(), anyString());
        assertThat(feed(owner).path("totalElements").asLong()).isOne();
        assertThat(processingRow(owner, policyId).getRecommendationId())
                .isEqualTo(feed(owner).at("/content/0/id").asLong());
    }

    @Test
    void managerDashboardCountsAutomaticallyPublishedReports() throws Exception {
        User early = applicant("2026-12-01");
        User late = applicant("2028-12-01");
        User manager = managerOf(early);
        link(manager, late);
        long policyId = ingest(SourceType.PRIMARY);
        awaitLinked(policyId, 2);

        JsonNode dashboard = json(get("/api/v1/manager/dashboard"), manager);
        assertThat(dashboard.path("openImpactsBySeverity").path("HIGH").asLong()).isOne();
        assertThat(dashboard.path("openImpactsBySeverity").path("LOW").asLong()).isOne();
    }

    @Test
    void newsResultsStayProcessingRecordsAndNeverReachTheFeed() throws Exception {
        User owner = applicant("2026-12-01");
        long policyId = ingest(SourceType.SECONDARY);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(rows(policyId)).hasSize(1));

        assertThat(processingRow(owner, policyId).getRecommendationId()).isNull();
        assertThat(feed(owner).path("totalElements").asLong()).isZero();
    }

    private long ingest(SourceType sourceType) {
        String externalId = "auto-" + UUID.randomUUID();
        long id = ingestion.ingest(new IngestPolicyRequest(externalId, "Student expiry rule", "DHS", "F-1",
                PolicyStatus.FINAL, sourceType, LocalDate.now(), null, "https://example.org/" + externalId,
                "Students must check their visa expiry dates.")).policyId();
        createdPolicies.add(id);
        return id;
    }

    private void awaitLinked(long policyId, int count) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(rows(policyId))
                .hasSize(count).allSatisfy(row -> assertThat(row.getRecommendationId()).isNotNull()));
    }

    private List<ImpactAssessment> rows(long policyId) {
        return results.findAll().stream().filter(row -> row.getPolicyId().equals(policyId)).toList();
    }

    private ImpactAssessment processingRow(User owner, long policyId) {
        return rows(policyId).stream().filter(row -> row.getUserId().equals(owner.getId())).findFirst().orElseThrow();
    }

    private JsonNode feed(User user) throws Exception {
        return json(get("/api/v1/impacts"), user);
    }

    private List<JsonNode> queue(User reviewer) throws Exception {
        List<JsonNode> items = new ArrayList<>();
        json(get("/api/v1/review/impacts"), reviewer).forEach(items::add);
        return items;
    }

    private JsonNode json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            User user) throws Exception {
        return mapper.readTree(mvc.perform(request.header("Authorization", token(user)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private User applicant(String expiry) {
        return applicant(expiry, "F-1");
    }

    private User applicant(String expiry, String visaType) {
        User user = users.saveAndFlush(new User("Auto applicant", "auto-" + UUID.randomUUID() + "@example.org", "unused"));
        createdUsers.add(user.getId());
        UserProfile profile = UserProfile.registrationProfile(user.getId(), visaType, null, null, null, null);
        profile.setVisaExpiryDate(LocalDate.parse(expiry));
        profiles.saveAndFlush(profile);
        for (var field : List.of(ProfileFieldDefinition.VISA_TYPE, ProfileFieldDefinition.VISA_EXPIRY_DATE)) {
            consents.saveAndFlush(new ProfileFieldConsent(user.getId(), field, Instant.now()));
        }
        return user;
    }

    private User managerOf(User applicant) {
        User manager = new User("Auto manager", "auto-manager-" + UUID.randomUUID() + "@example.org", "unused");
        manager.setRole(UserRole.MANAGER);
        manager = users.saveAndFlush(manager);
        createdUsers.add(manager.getId());
        link(manager, applicant);
        return manager;
    }

    private void link(User manager, User applicant) {
        jdbc.update("INSERT INTO manager_user_memberships (manager_id, user_id) VALUES (?, ?)",
                manager.getId(), applicant.getId());
    }

    private String token(User user) {
        return "Bearer " + jwt.generateAccessToken(user);
    }

    private static String candidate(String urgency, int confidence, boolean review, String summary) {
        return """
                {"urgency":"%s","confidence":%d,"summary":"%s",
                 "reasons":["Visa expiry timing"],"recommendedActions":["Check the official rule"],
                 "missingInformation":[],"requiresHumanReview":%s}
                """.formatted(urgency, confidence, summary, review);
    }
}
