package mygrant.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import mygrant.auth.JwtService;
import mygrant.policies.*;
import mygrant.support.PgvectorIntegrationTestBase;
import mygrant.user.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
@AutoConfigureMockMvc
@Transactional
class SeverityContractIntegrationTest extends PgvectorIntegrationTestBase {
    @Autowired MockMvc mvc;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired ObjectMapper mapper;
    @Autowired ImpactAssessmentRepository assessments;
    @Autowired PolicyRecommendationStore recommendations;
    @Autowired UserRepository users;
    @Autowired UserProfileRepository profiles;
    @Autowired PolicyDocumentRepository policies;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @MockBean VectorStore vectors;
    @MockBean ProfileImpactAssessmentService generator;

    @ParameterizedTest
    @EnumSource(ImpactUrgency.class)
    void canonicalValuesParsePersistAndReturnThroughBothApiFields(ImpactUrgency urgency) throws Exception {
        var owner = account(UserRole.APPLICANT);
        var policy = policies.saveAndFlush(new PolicyDocument(UUID.randomUUID().toString(), "Rule", "DHS", "F-1",
                PolicyStatus.FINAL, SourceType.PRIMARY, LocalDate.now(), null,
                "https://example.org/rule", "Policy text", "0".repeat(64)));
        // Confident and independently verified, so the shared review policy publishes it at any urgency.
        var result = new ProfileImpactAssessment(urgency, 90, "Profile-specific summary", List.of("Reason"),
                List.of(), List.of(), false, "Test", "test",
                new JudgeEvaluation(JudgeEvaluation.Verdict.YES, "Supported", "test"), 1);
        var parsed = mapper.readValue(mapper.writeValueAsString(result), ProfileImpactAssessment.class);
        assertThat(parsed).isEqualTo(result);
        var saved = assessments.saveAndFlush(new ImpactAssessment(owner.getId(), policy.getId(), 1,
                AssessmentStatus.COMPLETED, urgency, 90, result.summary(), result.reasons(),
                result.recommendedActions(), false, AssessmentReviewStatus.NOT_REQUIRED, "Test", "test"));
        entityManager.clear();
        assertThat(assessments.findById(saved.getId()).orElseThrow().getUrgency()).isEqualTo(urgency);
        recommendations.getOrGenerate(owner.getEmail(), policy, () -> result);
        assertThat(jdbc.queryForObject("SELECT severity FROM policy_recommendations WHERE user_id = ?",
                String.class, owner.getId())).isEqualTo(urgency.name());
        mvc.perform(post("/api/v1/policies/" + policy.getId() + "/recommendation")
                        .header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.urgency").value(urgency.name()))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(get("/api/v1/impacts?severity=" + urgency.name()).header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].severity").value(urgency.name()))
                .andExpect(jsonPath("$.content[0].urgency").value(urgency.name()));
        // HIGH does not encode review status: human-approved HIGH results may require no further review.
        assertThat(saved.isRequiresReview()).isFalse();
        assertThat(saved.getReviewStatus()).isEqualTo(AssessmentReviewStatus.NOT_REQUIRED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CRITICAL", "WARNING", "INFORMATIONAL", "NO_IMPACT", "INFO", "UNAVAILABLE", "URGENT"})
    void unsupportedValuesAreRejectedByJsonFiltersAndReviewRequests(String unsupported) throws Exception {
        var reviewer = account(UserRole.ADMIN);
        assertThatThrownBy(() -> mapper.readValue("{\"urgency\":\"" + unsupported + "\"}", ProfileImpactAssessment.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        mvc.perform(get("/api/v1/impacts?severity=" + unsupported).header("Authorization", token(reviewer)))
                .andExpect(status().isBadRequest());
        String correction = """
                {"severity":"%s","confidence":90,"summary":"Correction",
                 "analysis":[],"actionItems":[],"missingInformation":[]}
                """.formatted(unsupported);
        mvc.perform(patch("/api/v1/review/impacts/1").header("Authorization", token(reviewer))
                        .contentType(MediaType.APPLICATION_JSON).content(correction))
                .andExpect(status().isBadRequest());
    }

    @Test
    void openApiDocumentsOnlyCanonicalSeverityAndSeparateAvailability() throws Exception {
        var spec = mapper.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        var schemas = spec.path("components").path("schemas");
        assertThat(enumValues(spec, schemas.path("ProfileImpactAssessment").path("properties").path("urgency")))
                .containsExactlyElementsOf(ImpactUrgency.labels());
        assertThat(enumValues(spec, schemas.path("ProfileImpactAssessment").path("properties").path("status")))
                .containsExactly("COMPLETED", "UNAVAILABLE", "AWAITING_REVIEW");
        assertThat(enumValues(spec, schemas.path("ImpactView").path("properties").path("severity")))
                .containsExactlyElementsOf(ImpactUrgency.labels());
        for (var parameter : spec.path("paths").path("/api/v1/impacts").path("get").path("parameters")) {
            if ("severity".equals(parameter.path("name").asText())) {
                assertThat(enumValues(spec, parameter.path("schema"))).containsExactlyElementsOf(ImpactUrgency.labels());
            }
        }
    }

    @Test
    void unavailableJsonHasNoSeverity() throws Exception {
        var result = new ProfileImpactAssessment(null, 0, "Try again later", List.of(), List.of(), List.of(),
                true, "Test", "test", null, 0);
        var json = mapper.readTree(mapper.writeValueAsString(result));
        assertThat(json.path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(json.path("urgency").isNull()).isTrue();
        assertThat(mapper.readValue(json.toString(), ProfileImpactAssessment.class)).isEqualTo(result);
    }

    private List<String> enumValues(JsonNode root, JsonNode schema) {
        if (schema.has("$ref")) schema = root.at(schema.path("$ref").asText().substring(1));
        return mapper.convertValue(schema.path("enum"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
    }

    private User account(UserRole role) {
        var user = new User("Severity test", UUID.randomUUID() + "@example.org", "unused");
        user.setRole(role);
        user = users.saveAndFlush(user);
        profiles.saveAndFlush(UserProfile.registrationProfile(user.getId(), "F-1", null, null, null, null));
        return user;
    }

    private String token(User user) { return "Bearer " + jwt.generateAccessToken(user); }
}
