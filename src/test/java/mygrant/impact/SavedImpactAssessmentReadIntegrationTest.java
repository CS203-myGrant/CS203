package mygrant.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import mygrant.auth.JwtService;
import mygrant.policies.*;
import mygrant.support.PgvectorIntegrationTestBase;
import mygrant.user.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "spring.application.name=saved-assessment-read-test"})
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SavedImpactAssessmentReadIntegrationTest extends PgvectorIntegrationTestBase {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PolicyDocumentRepository policies;
    @Autowired ImpactAssessmentRepository assessments;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockBean VectorStore vectors;
    @MockBean ProfileImpactAssessmentService generator;

    @Test
    void listAndDetailExposeSourceAndAssessmentFieldsWithoutGeneration() throws Exception {
        var owner = user();
        var saved = save(owner, ImpactUrgency.MEDIUM, AssessmentReviewStatus.APPROVED);
        var list = mvc.perform(get("/api/v1/impact-assessments").header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andReturn().getResponse().getContentAsString();
        var detail = mvc.perform(get("/api/v1/impact-assessments/" + saved.getId())
                        .header("Authorization", token(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(saved.getId()))
                .andExpect(jsonPath("$.policyId").value(saved.getPolicyId()))
                .andExpect(jsonPath("$.policyTitle").value("Saved policy"))
                .andExpect(jsonPath("$.sourceUrl").value("https://example.org/rule"))
                .andExpect(jsonPath("$.urgency").value("MEDIUM"))
                .andExpect(jsonPath("$.summary").value("Your personalised summary"))
                .andExpect(jsonPath("$.confidence").value(85))
                .andExpect(jsonPath("$.reasons[0]").value("Your dates match"))
                .andExpect(jsonPath("$.recommendedActions[0]").value("Check the source"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.reviewedByHuman").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(list).get("content").get(0)).isEqualTo(mapper.readTree(detail));
        verifyNoInteractions(generator, vectors);
    }

    @Test
    void urgencyOrderingDateTieBreakFilteringAndPaginationAreStable() throws Exception {
        var owner = user();
        var low = save(owner, ImpactUrgency.LOW, AssessmentReviewStatus.NOT_REQUIRED);
        var oldHigh = save(owner, ImpactUrgency.HIGH, AssessmentReviewStatus.NOT_REQUIRED);
        var none = save(owner, ImpactUrgency.NOT_APPLICABLE, AssessmentReviewStatus.NOT_REQUIRED);
        var medium = save(owner, ImpactUrgency.MEDIUM, AssessmentReviewStatus.NOT_REQUIRED);
        var newHigh = save(owner, ImpactUrgency.HIGH, AssessmentReviewStatus.NOT_REQUIRED);
        jdbc.update("UPDATE impact_assessments SET created_at = '2020-01-01' WHERE id = ?", oldHigh.getId());
        mvc.perform(get("/api/v1/impact-assessments").param("size", "2").param("sort", "id,asc")
                        .header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content[0].id").value(newHigh.getId()))
                .andExpect(jsonPath("$.content[1].id").value(oldHigh.getId()))
                .andExpect(jsonPath("$.content[0].reviewedByHuman").value(false));
        mvc.perform(get("/api/v1/impact-assessments").param("size", "2").param("page", "1")
                        .header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(medium.getId()))
                .andExpect(jsonPath("$.content[1].id").value(low.getId()));
        mvc.perform(get("/api/v1/impact-assessments").param("size", "2").param("page", "2")
                        .header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(none.getId()));
        for (var urgency : ImpactUrgency.values()) {
            mvc.perform(get("/api/v1/impact-assessments").param("urgency", urgency.name())
                            .header("Authorization", token(owner)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(urgency == ImpactUrgency.HIGH ? 2 : 1))
                    .andExpect(jsonPath("$.content[0].urgency").value(urgency.name()));
        }
        mvc.perform(get("/api/v1/impact-assessments").param("urgency", "HIGH").param("size", "1").param("page", "1")
                        .header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(oldHigh.getId()));
        verifyNoInteractions(generator, vectors);
    }

    @Test
    void hiddenAndForeignResultsAreExcludedFromPagesAndDetails() throws Exception {
        var owner = user();
        var foreign = save(user(), ImpactUrgency.HIGH, AssessmentReviewStatus.NOT_REQUIRED);
        var pending = save(owner, ImpactUrgency.HIGH, AssessmentReviewStatus.PENDING_REVIEW);
        var rejected = save(owner, ImpactUrgency.HIGH, AssessmentReviewStatus.REJECTED);
        var unavailable = assessments.saveAndFlush(new ImpactAssessment(owner.getId(), policy().getId(), 1,
                AssessmentStatus.UNAVAILABLE, null, null, "Provider failed", List.of(), List.of(), false,
                AssessmentReviewStatus.NOT_REQUIRED, "Test", "test"));
        mvc.perform(get("/api/v1/impact-assessments").header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        for (var saved : List.of(foreign, pending, rejected, unavailable)) {
            mvc.perform(get("/api/v1/impact-assessments/" + saved.getId()).header("Authorization", token(owner)))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/v1/impact-assessments/9223372036854775807").header("Authorization", token(owner)))
                .andExpect(status().isNotFound());
        verifyNoInteractions(generator, vectors);
    }

    @Test
    void policyFilterRetainsOwnerVisibilityAndPaginationRules() throws Exception {
        var owner = user();
        var stranger = user();
        var visible = save(owner, ImpactUrgency.HIGH, AssessmentReviewStatus.NOT_REQUIRED);
        save(owner, ImpactUrgency.MEDIUM, AssessmentReviewStatus.NOT_REQUIRED);
        var hidden = save(owner, ImpactUrgency.HIGH, AssessmentReviewStatus.PENDING_REVIEW);
        // A foreign approved row on the same policy must not enter the owner's page or count.
        assessments.saveAndFlush(new ImpactAssessment(stranger.getId(), visible.getPolicyId(), 1,
                AssessmentStatus.COMPLETED, ImpactUrgency.HIGH, 85, "Foreign summary", List.of(), List.of(),
                true, AssessmentReviewStatus.APPROVED, "Test", "test"));
        mvc.perform(get("/api/v1/impact-assessments").param("policyId", visible.getPolicyId().toString())
                        .param("size", "1").param("urgency", "HIGH").header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(visible.getId()));
        for (String policyId : List.of(hidden.getPolicyId().toString(), "9223372036854775807")) {
            mvc.perform(get("/api/v1/impact-assessments").param("policyId", policyId)
                            .header("Authorization", token(owner)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        }
        mvc.perform(get("/api/v1/impact-assessments").param("policyId", visible.getPolicyId().toString())
                        .param("urgency", "LOW").header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        verifyNoInteractions(generator, vectors);
    }

    @Test
    void authenticationAndInvalidParametersAreEnforced() throws Exception {
        var owner = user();
        mvc.perform(get("/api/v1/impact-assessments")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/impact-assessments/1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/impact-assessments").param("urgency", "CRITICAL")
                        .header("Authorization", token(owner))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/impact-assessments").param("size", "101")
                        .header("Authorization", token(owner))).andExpect(status().isBadRequest());
    }

    private ImpactAssessment save(User user, ImpactUrgency urgency, AssessmentReviewStatus review) {
        return assessments.saveAndFlush(new ImpactAssessment(user.getId(), policy().getId(), 1,
                AssessmentStatus.COMPLETED, urgency, 85, "Your personalised summary", List.of("Your dates match"),
                List.of("Check the source"), review != AssessmentReviewStatus.NOT_REQUIRED, review, "Test", "test"));
    }

    private User user() {
        return users.saveAndFlush(new User("Reader", UUID.randomUUID() + "@example.org", "unused"));
    }

    private PolicyDocument policy() {
        return policies.saveAndFlush(new PolicyDocument(UUID.randomUUID().toString(), "Saved policy", "DHS", "F-1",
                PolicyStatus.FINAL, SourceType.PRIMARY, LocalDate.now(), null,
                "https://example.org/rule", "Rule text", "hash"));
    }

    private String token(User user) { return "Bearer " + jwt.generateAccessToken(user); }
}
