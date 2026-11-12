package mygrant.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import mygrant.actions.ActionItemService;
import mygrant.auth.JwtService;
import mygrant.policies.*;
import mygrant.support.PgvectorIntegrationTestBase;
import mygrant.user.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "mygrant.impact.review-confidence-threshold=75"})
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReviewVisibilityIntegrationTest extends PgvectorIntegrationTestBase {
    @Autowired MockMvc mvc;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired UserProfileRepository profiles;
    @Autowired PolicyDocumentRepository policies;
    @Autowired PolicyRecommendationStore recommendations;
    @Autowired ImpactAssessmentRepository assessments;
    @Autowired ActionItemService actions;
    @Autowired ImpactReviewService review;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @MockBean VectorStore vectors;
    @MockBean ProfileImpactAssessmentService generator;

    @ParameterizedTest
    @CsvSource({"74,false,true", "75,false,false", "76,false,false", "95,true,true"})
    void recommendationRoutesReviewAndWithholdsItsBodyUntilApproval(int confidence, boolean modelReview,
            boolean pending) throws Exception {
        var owner = account(UserRole.APPLICANT);
        var policy = policy();
        when(generator.assess(eq(owner.getEmail()), any(PolicyDocument.class), any(), any()))
                .thenReturn(candidate(confidence, modelReview));
        var response = mvc.perform(post("/api/v1/policies/" + policy.getId() + "/recommendation")
                        .header("Authorization", token(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response.contains("Private profile date analysis")).isEqualTo(!pending);
        Long id = jdbc.queryForObject("SELECT id FROM policy_recommendations WHERE user_id = ?", Long.class, owner.getId());
        var state = jdbc.queryForMap("SELECT requires_review, review_status, publication_status FROM policy_recommendations WHERE id = ?", id);
        assertThat(state.get("requires_review")).isEqualTo(pending);
        assertThat(state.get("review_status")).isEqualTo(pending ? "PENDING_REVIEW" : "NOT_REQUIRED");
        assertThat(state.get("publication_status")).isEqualTo(pending ? "PENDING_REVIEW" : "PUBLISHED");
        assertVisible(owner, id, pending ? 0 : 1);
        assertThat(recommendations.findCurrent(owner.getEmail(), policy).isPresent()).isEqualTo(!pending);
        if (pending) {
            assertThat(actions.itemsForAssessments(owner.getId(), List.of(id))).isEmpty();
            // Repeated user requests cannot replace/bypass the saved pending candidate.
            mvc.perform(post("/api/v1/policies/" + policy.getId() + "/recommendation")
                            .header("Authorization", token(owner))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.summary").value("This assessment is awaiting review."))
                    .andExpect(jsonPath("$.recommendedActions").isEmpty());
            verify(generator, times(1)).assess(eq(owner.getEmail()), any(PolicyDocument.class), any(), any());
            var reviewer = account(UserRole.ADMIN);
            // A human can approve an assessment even below the model-confidence threshold.
            review.publishCorrection(id, reviewer.getEmail(), new ImpactReviewController.Correction(
                    "HIGH", 50, "Approved summary", List.of("Checked the source"),
                    List.of("Read the official source"), List.of()));
            assertVisible(owner, id, 1);
            assertThat(recommendations.findCurrent(owner.getEmail(), policy)).isPresent();
            assertThat(actions.itemsForAssessments(owner.getId(), List.of(id))).containsKey(id);
        }
    }

    @Test
    void rejectedAssessmentAndPreviouslyCreatedActionsAreHiddenAcrossEveryRead() throws Exception {
        var owner = account(UserRole.APPLICANT);
        var policy = policy();
        recommendations.getOrGenerate(owner.getEmail(), policy, () -> candidate(90, false));
        Long id = jdbc.queryForObject("SELECT id FROM policy_recommendations WHERE user_id = ?", Long.class, owner.getId());
        Long itemId = jdbc.queryForObject("SELECT id FROM action_items WHERE assessment_id = ?", Long.class, id);
        // Retain PUBLISHED deliberately: review status alone must still prevent disclosure.
        jdbc.update("UPDATE policy_recommendations SET requires_review = TRUE, review_status = 'REJECTED', reviewed_by_human = TRUE WHERE id = ?", id);
        assertVisible(owner, id, 0);
        assertThat(actions.itemsForAssessments(owner.getId(), List.of(id))).isEmpty();
        assertThat(recommendations.findCurrent(owner.getEmail(), policy)).isEmpty();
        assertThat(recommendations.getOrGenerate(owner.getEmail(), policy, () -> {
            throw new AssertionError("Rejected result was regenerated");
        }).recommendedActions()).isEmpty();
        mvc.perform(patch("/api/v1/actions/" + itemId).header("Authorization", token(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerIsolationAppliesToBothAssessmentStores() throws Exception {
        var owner = account(UserRole.APPLICANT);
        var stranger = account(UserRole.APPLICANT);
        var policy = policy();
        recommendations.getOrGenerate(owner.getEmail(), policy, () -> candidate(90, false));
        Long legacyId = jdbc.queryForObject("SELECT id FROM policy_recommendations WHERE user_id = ?", Long.class, owner.getId());
        var saved = assessments.saveAndFlush(new ImpactAssessment(owner.getId(), policy.getId(), 1,
                AssessmentStatus.COMPLETED, ImpactUrgency.MEDIUM, 90, "Only owner can see this",
                List.of("Reason"), List.of("Read the source"), false, AssessmentReviewStatus.NOT_REQUIRED, "Test", "test"));
        mvc.perform(get("/api/v1/impacts/" + legacyId).header("Authorization", token(stranger)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/impacts").header("Authorization", token(stranger)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/actions").header("Authorization", token(stranger)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/impact-assessments/" + saved.getId()).header("Authorization", token(stranger)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/impact-assessments").header("Authorization", token(stranger)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/impact-assessments/" + saved.getId()).header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary").value("Only owner can see this"));
    }

    @Test
    void aPublishedLegacyModelReviewFlagCannotBypassTheVisibilityGate() throws Exception {
        var owner = account(UserRole.APPLICANT);
        var policy = policy();
        recommendations.getOrGenerate(owner.getEmail(), policy, () -> candidate(90, false));
        Long id = jdbc.queryForObject("SELECT id FROM policy_recommendations WHERE user_id = ?", Long.class, owner.getId());
        jdbc.update("UPDATE policy_recommendations SET assessment_json = ?, version = version + 1 WHERE id = ?", mapper.writeValueAsString(candidate(90, true)), id);
        assertVisible(owner, id, 0);
        assertThat(actions.itemsForAssessments(owner.getId(), List.of(id))).isEmpty();
    }

    @Test
    void pendingIngestionResultCannotBeBypassedThroughOnDemandGeneration() throws Exception {
        var owner = account(UserRole.APPLICANT);
        var policy = policy();
        var saved = assessments.saveAndFlush(new ImpactAssessment(owner.getId(), policy.getId(), 1,
                AssessmentStatus.COMPLETED, ImpactUrgency.MEDIUM, 50, "Saved private candidate",
                List.of("Private reasons"), List.of("Private action"), true, AssessmentReviewStatus.PENDING_REVIEW,
                "Test", "test"));
        mvc.perform(post("/api/v1/policies/" + policy.getId() + "/recommendation").header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary").value("This assessment is awaiting review."))
                .andExpect(jsonPath("$.recommendedActions").isEmpty());
        assertThat(recommendations.findCurrent(owner.getEmail(), policy)).isEmpty();
        verifyNoInteractions(generator);
        // CS203-42 will own this transition; a human-approved low-confidence result becomes readable.
        jdbc.update("UPDATE impact_assessments SET review_status = 'APPROVED' WHERE id = ?", saved.getId());
        // Clear the test's persistence context so the next request sees the actual updated state.
        entityManager.clear();
        mvc.perform(post("/api/v1/policies/" + policy.getId() + "/recommendation").header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary").value("Saved private candidate"));
        assertThat(recommendations.findCurrent(owner.getEmail(), policy)).isPresent();
        verifyNoInteractions(generator);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM policy_recommendations WHERE user_id = ?",
                Integer.class, owner.getId())).isZero();
    }

    private void assertVisible(User owner, Long id, int count) throws Exception {
        mvc.perform(get("/api/v1/impacts").header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(count));
        mvc.perform(get("/api/v1/impacts/" + id).header("Authorization", token(owner)))
                .andExpect(count == 0 ? status().isNotFound() : status().isOk());
        mvc.perform(get("/api/v1/impacts/consolidated").header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.breakdown.length()").value(count));
        mvc.perform(get("/api/v1/actions").header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(count));
    }

    private ProfileImpactAssessment candidate(int confidence, boolean review) {
        return new ProfileImpactAssessment(ImpactUrgency.MEDIUM, confidence, "Private profile date analysis",
                List.of("Private reasons"), List.of("Read the official source"), List.of(), review,
                "Test", "test", null, 1);
    }

    private User account(UserRole role) {
        var user = new User("Review test", UUID.randomUUID() + "@example.org", "unused");
        user.setRole(role);
        user = users.saveAndFlush(user);
        profiles.saveAndFlush(UserProfile.registrationProfile(user.getId(), "F-1", null, null, null, null));
        return user;
    }

    private PolicyDocument policy() {
        return policies.saveAndFlush(new PolicyDocument(UUID.randomUUID().toString(), "Rule", "DHS", "F-1",
                PolicyStatus.FINAL, SourceType.PRIMARY, LocalDate.now(), null, "https://example.org/rule", "Rule text", "hash"));
    }

    private String token(User owner) { return "Bearer " + jwt.generateAccessToken(owner); }
}
