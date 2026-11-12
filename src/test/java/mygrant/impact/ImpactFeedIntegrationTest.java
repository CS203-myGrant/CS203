package mygrant.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import mygrant.actions.ActionItemService;
import mygrant.auth.JwtService;
import mygrant.policies.PolicyDocument;
import mygrant.policies.PolicyDocumentRepository;
import mygrant.policies.PolicyStatus;
import mygrant.policies.PolicyRecommendationStore;
import mygrant.policies.SourceType;
import mygrant.policies.RestrictionCategory;
import mygrant.policies.RestrictionScope;
import mygrant.policies.PolicyCountryRestriction;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;
import mygrant.user.UserProfile;
import mygrant.user.UserProfileRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ImpactFeedIntegrationTest extends mygrant.support.PgvectorIntegrationTestBase {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired UserProfileRepository userProfiles;
    @Autowired PolicyDocumentRepository policies;
    @Autowired PolicyRecommendationStore recommendations;
    @Autowired ActionItemService actionItems;

    @Test
    void listDetailArchiveAndAccountIsolation() throws Exception {
        User owner = account(UserRole.APPLICANT);
        User stranger = account(UserRole.APPLICANT);
        PolicyDocument policy = policy();
        long low = insert(owner, policy, "LOW", "PUBLISHED", approved(ImpactUrgency.LOW, "Reminder"));
        long critical = insert(owner, policy, "HIGH", "PUBLISHED",
                approved(ImpactUrgency.HIGH, "Urgent review"));
        long pending = insert(owner, policy, "MEDIUM", "PENDING_REVIEW",
                pending(ImpactUrgency.MEDIUM, "Do not show this draft"));

        mvc.perform(get("/api/v1/impacts?size=1").header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(critical))
                // CS203-175: every response says whether the policy sources are still updating.
                .andExpect(jsonPath("$.content[0].sourceFreshness.stale").isBoolean())
                .andExpect(jsonPath("$.content[0].sourceFreshness.sources").isArray());
        mvc.perform(get("/api/v1/impacts?sort=date,asc&severity=LOW")
                        .header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(low));
        mvc.perform(get("/api/v1/impacts/" + critical).header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary").value("Urgent review"))
                .andExpect(jsonPath("$.citations[*].url").value(org.hamcrest.Matchers.containsInAnyOrder(
                        policy.getSourceUrl(), "https://www.whitehouse.gov/proclamation",
                        "https://www.whitehouse.gov/proclamation#section-2")))
                .andExpect(jsonPath("$.actionItems[0].text").value("Check the official rule"))
                .andExpect(jsonPath("$.sourceFreshness.stale").isBoolean());
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
        long newer = insert(owner, policy, "HIGH", "PUBLISHED",
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
        link(manager, owner);
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
                                {"severity":"HIGH","confidence":85,
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
    void linkedManagerVerifiesUnchangedDraftWithAuditedNoteAndAccountScope() throws Exception {
        User owner = account(UserRole.APPLICANT);
        User manager = account(UserRole.MANAGER);
        User unrelated = account(UserRole.MANAGER);
        link(manager, owner);
        long id = insert(owner, policy(), "MEDIUM", "PENDING_REVIEW",
                pending(ImpactUrgency.MEDIUM, "Original AI summary"));
        String decision = "{\"decision\":\"VERIFY\",\"version\":1,\"note\":\"Checked the official rule and profile.\"}";

        decide(owner, id, decision).andExpect(status().isForbidden());
        decide(unrelated, id, decision).andExpect(status().isNotFound());
        decide(manager, id, "{\"decision\":\"VERIFY\",\"version\":1,\"note\":\" \"}")
                .andExpect(status().isBadRequest());
        decide(manager, id, "{\"decision\":\"VERIFY\",\"version\":2,\"note\":\"Checked source.\"}")
                .andExpect(status().isConflict());
        decide(manager, id, decision).andExpect(status().isOk())
                .andExpect(jsonPath("$.publicationStatus").value("PUBLISHED"))
                .andExpect(jsonPath("$.reviewedByHuman").value(true))
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(get("/api/v1/impacts/" + id).header("Authorization", token(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("Original AI summary"))
                .andExpect(jsonPath("$.reviewedByHuman").value(true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM action_items WHERE assessment_id = ?",
                Long.class, id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM impact_assessment_revisions WHERE assessment_id = ?",
                Long.class, id)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT note FROM impact_review_decisions WHERE assessment_id = ?",
                String.class, id)).isEqualTo("Checked the official rule and profile.");
        decide(manager, id, decision).andExpect(status().isConflict());
    }

    @Test
    void adminRejectsPendingDraftWithoutExposingItToApplicant() throws Exception {
        User owner = account(UserRole.APPLICANT);
        User admin = account(UserRole.ADMIN);
        long id = insert(owner, policy(), "HIGH", "PENDING_REVIEW",
                pending(ImpactUrgency.HIGH, "Unsafe draft"));
        decide(admin, id, "{\"decision\":\"REJECT\",\"version\":1,\"note\":\"This claim is unsupported by the source.\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.publicationStatus").value("REJECTED"));
        mvc.perform(get("/api/v1/impacts/" + id).header("Authorization", token(owner)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/review/impacts").header("Authorization", token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT decision FROM impact_review_decisions WHERE assessment_id = ?",
                String.class, id)).isEqualTo("REJECT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM impact_assessment_revisions WHERE assessment_id = ?",
                Long.class, id)).isEqualTo(2L);
        mvc.perform(patch("/api/v1/review/impacts/" + id).header("Authorization", token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"severity":"LOW","confidence":90,"summary":"Trying to republish",
                                 "analysis":[],"actionItems":[],"missingInformation":[]}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void policyDetailFindsOnlyTheOwnersNewestPublishedAssessment() throws Exception {
        User owner = account(UserRole.APPLICANT);
        User stranger = account(UserRole.APPLICANT);
        PolicyDocument policy = policy();
        long first = insert(owner, policy, "LOW", "PUBLISHED", approved(ImpactUrgency.LOW, "First advice"));
        insert(owner, policy, "HIGH", "PENDING_REVIEW", pending(ImpactUrgency.HIGH, "Hidden draft"));
        String path = "/api/v1/impacts/policy/" + policy.getId() + "/latest";
        mvc.perform(get(path).header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(first));
        mvc.perform(get(path).header("Authorization", token(stranger))).andExpect(status().isNotFound());
        long newest = insert(owner, policy, "MEDIUM", "PUBLISHED",
                approved(ImpactUrgency.MEDIUM, "Newest advice"));
        mvc.perform(get(path).header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(newest))
                .andExpect(jsonPath("$.version").value(1));
    }

    private org.springframework.test.web.servlet.ResultActions decide(User reviewer, long id, String body)
            throws Exception {
        return mvc.perform(post("/api/v1/review/impacts/" + id + "/decision")
                .header("Authorization", token(reviewer)).contentType(MediaType.APPLICATION_JSON)
                .content(body));
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

    @Test
    void consolidatedViewCombinesOverlappingPolicyAssessmentsButKeepsAtomicBreakdown() throws Exception {
        User owner = account(UserRole.APPLICANT);
        userProfiles.saveAndFlush(UserProfile.registrationProfile(owner.getId(), "H-1B", null, null, null, null));
        String proclamation10949 = "https://www.federalregister.gov/documents/2025/06/10/2025-10669/"
                + "restricting-the-entry-of-foreign-nationals-to-protect-the-united-states-from-foreign";
        String proclamation10973 = "https://www.federalregister.gov/documents/2025/09/24/2025-18601/"
                + "restriction-on-entry-of-certain-nonimmigrant-workers";
        PolicyDocument nationalityPolicy = policies.saveAndFlush(new PolicyDocument(
                "2025-10669-" + UUID.randomUUID(), "Proclamation 10949 nationality restriction", "State",
                "ENTRY_RESTRICTION", PolicyStatus.FINAL, SourceType.PRIMARY, LocalDate.of(2025, 6, 10),
                LocalDate.of(2025, 6, 9), proclamation10949, "Historical source fixture", "hash",
                java.util.Set.of("AF"), java.util.Set.of(RestrictionCategory.ENTRY_RESTRICTION),
                List.of(proclamation10949), List.of(new PolicyCountryRestriction("AF", RestrictionScope.FULL,
                        proclamation10949 + "#section-2", "Exceptions for specified travelers."))));
        PolicyDocument visaPolicy = policies.saveAndFlush(new PolicyDocument(
                "2025-18601-" + UUID.randomUUID(), "Proclamation 10973 H-1B entry restriction", "Executive Office",
                "H-1B", PolicyStatus.FINAL, SourceType.PRIMARY, LocalDate.of(2025, 9, 24),
                LocalDate.of(2025, 9, 21), proclamation10973, "Historical source fixture", "hash",
                java.util.Set.of(), java.util.Set.of(RestrictionCategory.WORK_AUTHORIZATION),
                List.of(proclamation10973), List.of()));
        insert(owner, visaPolicy, "HIGH", "PUBLISHED", approved(ImpactUrgency.HIGH, "H-1B impact"));
        // A newer draft for the same policy must not replace the current published assessment.
        insert(owner, visaPolicy, "HIGH", "PENDING_REVIEW", pending(ImpactUrgency.HIGH, "Unreviewed draft"));
        insert(owner, nationalityPolicy, "MEDIUM", "PUBLISHED", new ProfileImpactAssessment(
                ImpactUrgency.MEDIUM, 60, "Nationality impact", List.of("Country rule applies"),
                List.of("Check the source"), List.of("countryOfCitizenship"), true, "OpenAI", "test",
                new JudgeEvaluation(JudgeEvaluation.Verdict.YES, "Supported with uncertainty", "test"), 1));

        mvc.perform(get("/api/v1/impacts/consolidated").header("Authorization", token(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netStatus").value("HIGH"))
                .andExpect(jsonPath("$.breakdown.length()").value(2))
                .andExpect(jsonPath("$.breakdown[*].summary")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("H-1B impact", "Nationality impact")))
                .andExpect(jsonPath("$.citations.length()").value(3))
                .andExpect(jsonPath("$.citations[*].url").value(org.hamcrest.Matchers.containsInAnyOrder(
                        proclamation10949, proclamation10949 + "#section-2", proclamation10973)))
                .andExpect(jsonPath("$.uncertaintyNotes[0]")
                        .value("Proclamation 10949 nationality restriction: countryOfCitizenship"))
                .andExpect(jsonPath("$.uncertaintyNotes[1]").value(
                        "Proclamation 10949 nationality restriction: human review is recommended for this assessment."));
    }

    @Test
    void consolidatedViewIsOwnerScopedAndExcludesArchivedAssessments() throws Exception {
        User owner = account(UserRole.APPLICANT);
        User stranger = account(UserRole.APPLICANT);
        User withoutAssessments = account(UserRole.APPLICANT);
        PolicyDocument sharedPolicy = policy();

        long archivedOlderAssessment = insert(owner, sharedPolicy, "LOW", "PUBLISHED",
                approved(ImpactUrgency.LOW, "Archived older assessment"));
        insert(owner, sharedPolicy, "HIGH", "PUBLISHED",
                approved(ImpactUrgency.HIGH, "Owner current assessment"));
        insert(stranger, sharedPolicy, "MEDIUM", "PUBLISHED",
                approved(ImpactUrgency.MEDIUM, "Stranger private assessment"));
        jdbc.update("UPDATE policy_recommendations SET archived_at = now() WHERE id = ?", archivedOlderAssessment);

        PolicyDocument archivedOnlyPolicy = policy();
        long archivedOnlyAssessment = insert(owner, archivedOnlyPolicy, "MEDIUM", "PUBLISHED",
                approved(ImpactUrgency.MEDIUM, "Archived only assessment"));
        jdbc.update("UPDATE policy_recommendations SET archived_at = now() WHERE id = ?", archivedOnlyAssessment);

        mvc.perform(get("/api/v1/impacts/consolidated").header("Authorization", token(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.breakdown.length()").value(1))
                .andExpect(jsonPath("$.breakdown[0].summary").value("Owner current assessment"))
                .andExpect(jsonPath("$.netStatus").value("HIGH"));

        mvc.perform(get("/api/v1/impacts/consolidated").header("Authorization", token(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.breakdown.length()").value(1))
                .andExpect(jsonPath("$.breakdown[0].summary").value("Stranger private assessment"));

        mvc.perform(get("/api/v1/impacts/consolidated").header("Authorization", token(withoutAssessments)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netStatus").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.breakdown").isEmpty())
                .andExpect(jsonPath("$.citations").isEmpty())
                .andExpect(jsonPath("$.uncertaintyNotes").isEmpty());
    }

    private User account(UserRole role) {
        User user = new User("Impact test", "impact-" + UUID.randomUUID() + "@example.org",
                "unused");
        user.setRole(role);
        return users.saveAndFlush(user);
    }

    private void link(User manager, User applicant) {
        jdbc.update("INSERT INTO manager_user_memberships (manager_id, user_id) VALUES (?, ?)",
                manager.getId(), applicant.getId());
    }

    private String token(User user) {
        return "Bearer " + jwt.generateAccessToken(user);
    }

    private PolicyDocument policy() {
        return policies.saveAndFlush(new PolicyDocument("impact-" + UUID.randomUUID(),
                "Student status rule", "DHS", "F-1", PolicyStatus.FINAL, SourceType.PRIMARY,
                LocalDate.of(2026, 10, 1), null, "https://www.federalregister.gov/example",
                "Official source text", "hash", java.util.Set.of("AF"),
                java.util.Set.of(RestrictionCategory.ENTRY_RESTRICTION),
                List.of("https://www.whitehouse.gov/proclamation"), List.of(new PolicyCountryRestriction("AF",
                        RestrictionScope.FULL, "https://www.whitehouse.gov/proclamation#section-2",
                        "Exceptions apply to permanent residents and specified visa holders."))));
    }

    /** Saves the row directly, then generates its checklist as the two publish points do. */
    private long insert(User owner, PolicyDocument policy, String severity, String status,
            ProfileImpactAssessment assessment) throws Exception {
        long id = jdbc.queryForObject("""
                INSERT INTO policy_recommendations
                    (user_id, policy_id, input_hash, assessment_json, publication_status, severity)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, owner.getId(), policy.getId(), UUID.randomUUID().toString(),
                mapper.writeValueAsString(assessment), status, severity);
        actionItems.syncForAssessment(id);
        return id;
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
