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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import mygrant.auth.JwtService;
import mygrant.policies.PolicyDocument;
import mygrant.policies.PolicyDocumentRepository;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

/** CS203-97 / CS203-6: a categorised, linked report reaches the reviewer queue and is closed by review. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class RecommendationReportIntegrationTest extends mygrant.support.PgvectorIntegrationTestBase {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired PolicyDocumentRepository policies;

    @Test
    void reportIsLinkedCategorisedAndQueuedUntilACorrectionResolvesIt() throws Exception {
        User owner = account(UserRole.APPLICANT);
        User manager = account(UserRole.MANAGER);
        User otherManager = account(UserRole.MANAGER);
        link(manager, owner);
        long id = insert(owner, "PUBLISHED", ImpactUrgency.HIGH, "Do not travel before the deadline");

        report(owner, id, "WRONG_SEVERITY", "My program ends next year, so this is not urgent for me.", 1)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assessmentId").value(id))
                .andExpect(jsonPath("$.assessmentVersion").value(1))
                .andExpect(jsonPath("$.category").value("WRONG_SEVERITY"))
                .andExpect(jsonPath("$.status").value("OPEN"));

        // The assessment stays visible to its owner while the report is open.
        mvc.perform(get("/api/v1/impacts/" + id).header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary").value("Do not travel before the deadline"));

        // It reaches the linked manager's review queue with the report attached; not an unlinked one's.
        mvc.perform(get("/api/v1/review/impacts").header("Authorization", token(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].applicantName").value("Report test"))
                .andExpect(jsonPath("$[0].applicantEmail").value(owner.getEmail()))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].draftUrgency").value("HIGH"))
                .andExpect(jsonPath("$[0].draftAnalysis[0]").value("Source matches profile"))
                .andExpect(jsonPath("$[0].reports[0].category").value("WRONG_SEVERITY"))
                .andExpect(jsonPath("$[0].reports[0].details").value("My program ends next year, so this is not urgent for me."));
        mvc.perform(get("/api/v1/review/impacts").header("Authorization", token(otherManager)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        // The queue badge counts it for the linked manager only.
        mvc.perform(get("/api/v1/review/impacts/reports/count").header("Authorization", token(manager)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.openReports").value(1))
                .andExpect(jsonPath("$.reportedAssessments").value(1));
        mvc.perform(get("/api/v1/review/impacts/reports/count").header("Authorization", token(otherManager)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.openReports").value(0));
        mvc.perform(get("/api/v1/review/impacts/reports/count").header("Authorization", token(owner)))
                .andExpect(status().isForbidden());

        // A reported assessment cannot be "corrected" without a note, or without changing anything.
        String changed = correction("LOW", 90, "Not urgent: your program ends after the rule takes effect.",
                List.of("Program end date checked"), List.of("Review again before booking travel"), null);
        publish(manager, id, changed).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Explain the correction")));
        String unchanged = correction("HIGH", 90, "  Do not travel before the deadline ",
                List.of("Source matches profile"), List.of("Check the official rule"), "Looks right to me");
        publish(manager, id, unchanged).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Nothing in the assessment was changed")));
        mvc.perform(get("/api/v1/impacts/" + id).header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));

        publish(manager, id, correction("LOW", 90, "Not urgent: your program ends after the rule takes effect.",
                List.of("Program end date checked"), List.of("Review again before booking travel"),
                "Your program end date is after the rule's effective date, so this is LOW."))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/review/impacts").header("Authorization", token(manager)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/review/impacts/reports/count").header("Authorization", token(manager)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.openReports").value(0));
        mvc.perform(get("/api/v1/impacts/" + id + "/reports").header("Authorization", token(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("RESOLVED"))
                .andExpect(jsonPath("$[0].resolvedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].resolutionNote")
                        .value("Your program end date is after the rule's effective date, so this is LOW."));
        assertThat(jdbc.queryForObject("SELECT resolved_by_user_id FROM recommendation_reports WHERE assessment_id = ?",
                Long.class, id)).isEqualTo(manager.getId());
    }

    @Test
    void reviewerCanDismissAReportAndTheApplicantMayReportAgainLater() throws Exception {
        User owner = account(UserRole.APPLICANT);
        User admin = account(UserRole.ADMIN);
        long id = insert(owner, "PUBLISHED", ImpactUrgency.MEDIUM, "Check your I-20 dates");
        report(owner, id, "UNCLEAR", "Which dates?", 1).andExpect(status().isCreated());

        dismiss(owner, id, "Not allowed").andExpect(status().isForbidden());
        // The applicant must be told why.
        dismiss(admin, id, "  ").andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/review/impacts/" + id + "/reports/dismiss").header("Authorization", token(admin)))
                .andExpect(status().isBadRequest());
        dismiss(admin, id, "The dates are the I-20 program start and end dates.")
                .andExpect(status().isOk()).andExpect(jsonPath("$.dismissedReports").value(1));
        // Nothing left to dismiss.
        dismiss(admin, id, "Again").andExpect(status().isNotFound());

        // The applicant's bell shows the outcome and the note until they have seen it.
        User stranger = account(UserRole.APPLICANT);
        mvc.perform(get("/api/v1/report-updates").header("Authorization", token(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unseen").value(1))
                .andExpect(jsonPath("$.items[0].assessmentId").value(id))
                .andExpect(jsonPath("$.items[0].policyTitle").value("Student status rule"))
                .andExpect(jsonPath("$.items[0].status").value("DISMISSED"))
                .andExpect(jsonPath("$.items[0].resolutionNote").value("The dates are the I-20 program start and end dates."))
                .andExpect(jsonPath("$.items[0].seen").value(false));
        mvc.perform(get("/api/v1/report-updates").header("Authorization", token(stranger)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unseen").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(post("/api/v1/report-updates/seen").header("Authorization", token(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assessmentId\":" + id + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.marked").value(1));
        mvc.perform(get("/api/v1/report-updates").header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unseen").value(0))
                .andExpect(jsonPath("$.items[0].seen").value(true));

        // Dismissal leaves the assessment untouched.
        mvc.perform(get("/api/v1/impacts/" + id).header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.summary").value("Check your I-20 dates"));
        report(owner, id, "OTHER", "Still unclear after review.", 1).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/impacts/" + id + "/reports").header("Authorization", token(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].resolutionNote").doesNotExist())
                .andExpect(jsonPath("$[1].status").value("DISMISSED"))
                .andExpect(jsonPath("$[1].resolutionNote").value("The dates are the I-20 program start and end dates."));
    }

    @Test
    void applicantCanArchiveAndRestoreOnlyAnsweredReports() throws Exception {
        User owner = account(UserRole.APPLICANT);
        User stranger = account(UserRole.APPLICANT);
        User admin = account(UserRole.ADMIN);
        long id = insert(owner, "PUBLISHED", ImpactUrgency.LOW, "Reminder");
        report(owner, id, "UNCLEAR", "Which dates?", 1).andExpect(status().isCreated());
        long reportId = jdbc.queryForObject("SELECT id FROM recommendation_reports WHERE assessment_id = ?", Long.class, id);

        // Still waiting for review: cannot be archived.
        archive(owner, id, reportId, true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("still waiting")));

        dismiss(admin, id, "Correct as published.").andExpect(status().isOk());
        archive(stranger, id, reportId, true).andExpect(status().isNotFound());
        archive(owner, id, reportId, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").isNotEmpty());

        // Archived reports leave the bell (and count as seen) but stay in the assessment's history.
        mvc.perform(get("/api/v1/report-updates").header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unseen").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/impacts/" + id + "/reports").header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].archivedAt").isNotEmpty());

        archive(owner, id, reportId, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").doesNotExist());
        mvc.perform(get("/api/v1/report-updates").header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].seen").value(true));
    }

    private ResultActions archive(User user, long id, long reportId, boolean archived) throws Exception {
        return mvc.perform(patch("/api/v1/impacts/" + id + "/reports/" + reportId)
                .header("Authorization", token(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"archived\":" + archived + "}"));
    }

    @Test
    void pendingDraftsWithoutReportsStillPublishWithoutANote() throws Exception {
        User owner = account(UserRole.APPLICANT);
        User admin = account(UserRole.ADMIN);
        long id = insert(owner, "PENDING_REVIEW", ImpactUrgency.MEDIUM, "Draft held by the judge");
        publish(admin, id, correction("MEDIUM", 90, "Draft held by the judge",
                List.of("Source matches profile"), List.of("Check the official rule"), null))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsDuplicateStaleInvalidAndOtherUsersReports() throws Exception {
        User owner = account(UserRole.APPLICANT);
        User stranger = account(UserRole.APPLICANT);
        long id = insert(owner, "PUBLISHED", ImpactUrgency.LOW, "Reminder");
        long hidden = insert(owner, "PENDING_REVIEW", ImpactUrgency.LOW, "Unreviewed draft");

        report(owner, id, "OTHER", "Wrong", 2).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("has changed")));
        report(owner, id, "NOT_A_CATEGORY", "Wrong", 1).andExpect(status().isBadRequest());
        report(owner, id, "OTHER", "  ", 1).andExpect(status().isBadRequest());
        report(owner, id, "OTHER", "x".repeat(901), 1).andExpect(status().isBadRequest());
        report(stranger, id, "OTHER", "Not mine", 1).andExpect(status().isNotFound());
        report(owner, hidden, "OTHER", "Cannot see it", 1).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/impacts/" + id + "/reports").header("Authorization", token(stranger)))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendation_reports", Long.class)).isZero();
    }

    @Test
    void applicantMayReportSeveralProblemsUpToFiveOpenAtOnce() throws Exception {
        User owner = account(UserRole.APPLICANT);
        User admin = account(UserRole.ADMIN);
        long id = insert(owner, "PUBLISHED", ImpactUrgency.LOW, "Reminder");

        for (int i = 1; i <= 5; i++) {
            report(owner, id, "OTHER", "Problem " + i, 1).andExpect(status().isCreated());
        }
        report(owner, id, "OTHER", "Problem 6", 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("waiting for review")));

        // The queue lists the assessment once, carrying every open report.
        mvc.perform(get("/api/v1/review/impacts").header("Authorization", token(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].reports.length()").value(5));

        // Once reviewed, the applicant can report again and sees every outcome.
        dismiss(admin, id, "Reviewed all five; the assessment is correct.")
                .andExpect(status().isOk()).andExpect(jsonPath("$.dismissedReports").value(5));
        report(owner, id, "UNCLEAR", "Problem 6", 1).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/impacts/" + id + "/reports").header("Authorization", token(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[1].status").value("DISMISSED"));
    }

    private String correction(String severity, int confidence, String summary, List<String> analysis,
            List<String> actions, String note) throws Exception {
        return mapper.writeValueAsString(new ImpactReviewController.Correction(severity, confidence, summary,
                analysis, actions, List.of(), null, note));
    }

    private ResultActions publish(User reviewer, long id, String correction) throws Exception {
        return mvc.perform(patch("/api/v1/review/impacts/" + id).header("Authorization", token(reviewer))
                .contentType(MediaType.APPLICATION_JSON).content(correction));
    }

    private ResultActions dismiss(User reviewer, long id, String note) throws Exception {
        return mvc.perform(post("/api/v1/review/impacts/" + id + "/reports/dismiss")
                .header("Authorization", token(reviewer)).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("note", note))));
    }

    private ResultActions report(User user, long id, String category, String details, int version) throws Exception {
        String body = mapper.writeValueAsString(java.util.Map.of("category", category, "details", details, "version", version));
        return mvc.perform(post("/api/v1/impacts/" + id + "/reports").header("Authorization", token(user))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private User account(UserRole role) {
        User user = new User("Report test", "report-" + UUID.randomUUID() + "@example.org", "unused");
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

    private long insert(User owner, String status, ImpactUrgency urgency, String summary) throws Exception {
        PolicyDocument policy = policies.saveAndFlush(new PolicyDocument("report-" + UUID.randomUUID(),
                "Student status rule", "DHS", "F-1", PolicyStatus.FINAL, SourceType.PRIMARY,
                LocalDate.of(2026, 10, 1), null, "https://www.federalregister.gov/example",
                "Official source text", "hash", java.util.Set.of(), java.util.Set.of(), List.of(), List.of()));
        var assessment = new ProfileImpactAssessment(urgency, 90, summary, List.of("Source matches profile"),
                List.of("Check the official rule"), List.of(), false, "OpenAI", "test",
                new JudgeEvaluation(JudgeEvaluation.Verdict.YES, "Supported", "test"), 1);
        return jdbc.queryForObject("""
                INSERT INTO policy_recommendations
                    (user_id, policy_id, input_hash, assessment_json, publication_status, severity)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, owner.getId(), policy.getId(), UUID.randomUUID().toString(),
                mapper.writeValueAsString(assessment), status, urgency.name());
    }
}
