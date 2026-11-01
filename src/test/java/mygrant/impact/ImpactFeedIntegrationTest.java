package mygrant.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import mygrant.auth.JwtService;
import mygrant.policies.PolicyDocument;
import mygrant.policies.PolicyDocumentRepository;
import mygrant.policies.PolicyStatus;
import mygrant.policies.PolicyRecommendationStore;
import mygrant.policies.SourceType;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ImpactFeedIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired PolicyDocumentRepository policies;
    @Autowired PolicyRecommendationStore recommendations;

    @Test
    void listDetailArchiveAndAccountIsolation() throws Exception {
        User owner = account(UserRole.APPLICANT);
        User stranger = account(UserRole.APPLICANT);
        PolicyDocument policy = policy();
        long low = insert(owner, policy, "LOW", "PUBLISHED", approved(ImpactUrgency.LOW, "Reminder"));
        long critical = insert(owner, policy, "CRITICAL", "PUBLISHED",
                approved(ImpactUrgency.HIGH, "Urgent review"));
        long pending = insert(owner, policy, "MEDIUM", "PENDING_REVIEW",
                pending(ImpactUrgency.MEDIUM, "Do not show this draft"));

        mvc.perform(get("/api/v1/impacts?size=1").header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(critical));
        mvc.perform(get("/api/v1/impacts?sort=date,asc&severity=LOW")
                        .header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(low));
        mvc.perform(get("/api/v1/impacts/" + critical).header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary").value("Urgent review"))
                .andExpect(jsonPath("$.citations[0].url").value(policy.getSourceUrl()))
                .andExpect(jsonPath("$.actionItems[0].text").value("Check the official rule"));
        mvc.perform(get("/api/v1/impacts/" + pending).header("Authorization", token(owner)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/impacts/" + critical).header("Authorization", token(stranger)))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/impacts/" + critical).header("Authorization", token(stranger))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"archived\":true}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/impacts").header("Authorization", token(stranger)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));

        for (int i = 0; i < 2; i++) {
            mvc.perform(patch("/api/v1/impacts/" + critical).header("Authorization", token(owner))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"archived\":true}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.archived").value(true));
        }
        mvc.perform(get("/api/v1/impacts?archived=true").header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM impact_archive_events WHERE assessment_id = ?",
                Long.class, critical)).isEqualTo(1L);
        long newer = insert(owner, policy, "CRITICAL", "PUBLISHED",
                approved(ImpactUrgency.HIGH, "New rule version"));
        mvc.perform(get("/api/v1/impacts").header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(newer));
        mvc.perform(get("/api/v1/impacts?archived=true").header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(critical));
        mvc.perform(patch("/api/v1/impacts/" + critical).header("Authorization", token(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"archived\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.archived").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM impact_archive_events WHERE assessment_id = ?",
                Long.class, critical)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM impact_assessment_revisions WHERE assessment_id = ?",
                Long.class, critical)).isEqualTo(1L);
    }

    @Test
    void managerCorrectionPublishesHiddenDraftAndKeepsBothVersions() throws Exception {
        User owner = account(UserRole.APPLICANT);
        User manager = account(UserRole.MANAGER);
        PolicyDocument policy = policy();
        long id = insert(owner, policy, "MEDIUM", "PENDING_REVIEW",
                pending(ImpactUrgency.MEDIUM, "Unreviewed model draft"));
        mvc.perform(get("/api/v1/impacts/" + id).header("Authorization", token(owner)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/impacts").header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/review/impacts").header("Authorization", token(owner)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/review/impacts").header("Authorization", token(manager)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(patch("/api/v1/review/impacts/" + id)
                        .header("Authorization", token(manager)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"severity":"CRITICAL","confidence":85,
                                 "summary":"Manager corrected the assessment",
                                 "analysis":["The official text affects this profile"],
                                 "actionItems":["Check the source"],"missingInformation":[]}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewedByHuman").value(true));
        mvc.perform(get("/api/v1/impacts/" + id).header("Authorization", token(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("Manager corrected the assessment"))
                .andExpect(jsonPath("$.reviewedByHuman").value(true))
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(get("/api/v1/impacts").header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM impact_assessment_revisions WHERE assessment_id = ?",
                Long.class, id)).isEqualTo(2L);
    }

    @Test
    void regenerationKeepsTheOlderArchivedAssessment() throws Exception {
        User owner = account(UserRole.APPLICANT);
        PolicyDocument policy = policy();
        recommendations.getOrGenerate(owner.getEmail(), policy,
                () -> approved(ImpactUrgency.LOW, "Original policy text"));
        long original = jdbc.queryForObject("SELECT id FROM policy_recommendations WHERE user_id = ?",
                Long.class, owner.getId());
        mvc.perform(patch("/api/v1/impacts/" + original).header("Authorization", token(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"archived\":true}"))
                .andExpect(status().isOk());

        org.springframework.test.util.ReflectionTestUtils.setField(policy, "content", "Revised source text");
        recommendations.getOrGenerate(owner.getEmail(), policy,
                () -> approved(ImpactUrgency.HIGH, "New assessment for revised rule"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM policy_recommendations WHERE user_id = ?",
                Long.class, owner.getId())).isEqualTo(2L);
        mvc.perform(get("/api/v1/impacts").header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].summary").value("New assessment for revised rule"));
        mvc.perform(get("/api/v1/impacts?archived=true").header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(original));
    }

    private User account(UserRole role) {
        User user = new User("Impact test", "impact-" + UUID.randomUUID() + "@example.org",
                "unused", "F-1", null, null);
        user.setRole(role);
        return users.saveAndFlush(user);
    }

    private String token(User user) {
        return "Bearer " + jwt.generateAccessToken(user);
    }

    private PolicyDocument policy() {
        return policies.saveAndFlush(new PolicyDocument("impact-" + UUID.randomUUID(),
                "Student status rule", "DHS", "F-1", PolicyStatus.FINAL, SourceType.PRIMARY,
                LocalDate.of(2026, 10, 1), null, "https://www.federalregister.gov/example",
                "Official source text", "hash"));
    }

    private long insert(User owner, PolicyDocument policy, String severity, String status,
            ProfileImpactAssessment assessment) throws Exception {
        return jdbc.queryForObject("""
                INSERT INTO policy_recommendations
                    (user_id, policy_id, input_hash, assessment_json, publication_status, severity)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, owner.getId(), policy.getId(), UUID.randomUUID().toString(),
                mapper.writeValueAsString(assessment), status, severity);
    }

    private ProfileImpactAssessment approved(ImpactUrgency urgency, String summary) {
        return new ProfileImpactAssessment(urgency, 90, summary, List.of("Source matches profile"),
                List.of("Check the official rule"), List.of(), false, "OpenAI", "test",
                new JudgeEvaluation(JudgeEvaluation.Verdict.YES, "Supported", "test"), 1);
    }

    private ProfileImpactAssessment pending(ImpactUrgency urgency, String summary) {
        return new ProfileImpactAssessment(urgency, 70, summary, List.of("Draft reason"),
                List.of("Draft action"), List.of(), true, "OpenAI", "test",
                new JudgeEvaluation(JudgeEvaluation.Verdict.NO, "Needs correction", "test"), 1);
    }
}
