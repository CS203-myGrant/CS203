package mygrant.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
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
import mygrant.policies.PolicyCountryRestriction;
import mygrant.policies.PolicyDocument;
import mygrant.policies.PolicyDocumentRepository;
import mygrant.policies.PolicyRecommendationStore;
import mygrant.policies.PolicyStatus;
import mygrant.policies.RestrictionCategory;
import mygrant.policies.RestrictionScope;
import mygrant.policies.SourceType;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EntryRestrictionReviewIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PolicyDocumentRepository policies;
    @Autowired PolicyRecommendationStore recommendations;
    @Autowired ObjectMapper mapper;

    @Test
    void extractionReviewShowsSourceAndPersistsHumanCorrectedRules() throws Exception {
        User applicant = account(UserRole.APPLICANT, "applicant");
        User admin = account(UserRole.ADMIN, "admin");
        String content = "Sec. 4. Full Suspension of Entry. Entry of nationals of Afghanistan is suspended.\n"
                + "Sec. 6. Exceptions. This restriction does not apply to lawful permanent residents.\n"
                + "Sec. 7. Effective Date.";
        PolicyDocument policy = policies.saveAndFlush(new PolicyDocument("FR-REVIEW-" + UUID.randomUUID(),
                "Entry proclamation", "Department of State", "ENTRY_RESTRICTION", PolicyStatus.FINAL,
                SourceType.PRIMARY, LocalDate.now(), LocalDate.now(), "https://example.gov/proclamation",
                content, "review-hash", Set.of(), Set.of(RestrictionCategory.ENTRY_RESTRICTION), List.of(),
                List.<PolicyCountryRestriction>of(), true, false));
        recommendations.queueExtractionForReview(applicant, policy, "Country list could not be extracted.");
        Long reviewId = jdbc.queryForObject("SELECT id FROM policy_recommendations WHERE user_id = ? AND policy_id = ?",
                Long.class, applicant.getId(), policy.getId());

        mvc.perform(get("/api/v1/review/impacts").header("Authorization", token(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(reviewId))
                .andExpect(jsonPath("$[0].reviewType").value("Policy extractor"))
                .andExpect(jsonPath("$[0].sourceUrl").value(policy.getSourceUrl()))
                .andExpect(jsonPath("$[0].sourceExcerpt").value(org.hamcrest.Matchers.containsString("lawful permanent residents")));

        String correction = mapper.writeValueAsString(new ImpactReviewController.Correction("INFO", 100,
                "Confirmed full entry restriction for Afghanistan.", List.of("Reviewed source sections 4 and 6."),
                List.of(), List.of(), List.of(new ImpactReviewController.CountryRuleCorrection("AF", RestrictionScope.FULL))));
        mvc.perform(patch("/api/v1/review/impacts/" + reviewId).header("Authorization", token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(correction))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicationStatus").value("EXTRACTION_APPROVED"));

        assertThat(jdbc.queryForObject("SELECT extraction_review_required FROM policy_documents WHERE id = ?",
                Boolean.class, policy.getId())).isFalse();
        assertThat(jdbc.queryForObject("SELECT country_code FROM policy_affected_countries WHERE policy_id = ?",
                String.class, policy.getId())).isEqualTo("AF");
        assertThat(jdbc.queryForObject("SELECT restriction_scope FROM policy_country_restrictions WHERE policy_id = ?",
                String.class, policy.getId())).isEqualTo("FULL");
        assertThat(jdbc.queryForObject("SELECT exemption_wording FROM policy_country_restrictions WHERE policy_id = ?",
                String.class, policy.getId())).contains("lawful permanent residents");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM policy_recommendations WHERE id = ?", Long.class, reviewId))
                .isZero();
    }

    private User account(UserRole role, String prefix) {
        User user = new User("Review test", prefix + "-" + UUID.randomUUID() + "@example.org", "unused");
        user.setRole(role);
        return users.saveAndFlush(user);
    }

    private String token(User user) { return "Bearer " + jwt.generateAccessToken(user); }
}
