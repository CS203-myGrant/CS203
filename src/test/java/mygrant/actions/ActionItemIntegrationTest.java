package mygrant.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import mygrant.auth.JwtService;
import mygrant.impact.ImpactUrgency;
import mygrant.impact.JudgeEvaluation;
import mygrant.impact.ProfileImpactAssessment;
import mygrant.policies.PolicyDocument;
import mygrant.policies.PolicyDocumentRepository;
import mygrant.policies.PolicyRecommendationStore;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;
import mygrant.testclock.TestClock;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

/** CS203-4 end to end against the local PostgreSQL database; every test rolls back. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ActionItemIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired PolicyDocumentRepository policies;
    @Autowired PolicyRecommendationStore recommendations;
    @Autowired ActionItemService actionItems;
    @Autowired TestClock clock;

    @Test
    void publishingGeneratesOneLinkedItemPerRecommendedAction() throws Exception {
        User owner = account(UserRole.APPLICANT);
        PolicyDocument policy = policy(LocalDate.of(2027, 1, 15));
        String longAction = "Collect proof of enrolment, financial support and a valid passport before you "
                + "travel, and carry them with your signed I-20 when you re-enter the United States.";
        recommendations.getOrGenerate(owner.getEmail(), policy, () -> approved(ImpactUrgency.HIGH,
                List.of("Renew your I-20", "  File   Form I-765\n", longAction)));
        long assessment = jdbc.queryForObject("SELECT id FROM policy_recommendations WHERE user_id = ?",
                Long.class, owner.getId());

        List<Row> rows = rows(assessment);
        assertThat(rows).extracting(Row::position).containsExactly(1, 2, 3);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.userId()).isEqualTo(owner.getId());
            assertThat(row.priority()).isEqualTo("HIGH");
            assertThat(row.status()).isEqualTo("PENDING");
            assertThat(row.deadline()).isEqualTo(LocalDate.of(2027, 1, 15));
            assertThat(row.completedAt()).isNull();
            assertThat(row.dismissedAt()).isNull();
        });
        assertThat(rows.get(1).title()).isEqualTo("File   Form I-765");
        assertThat(rows.get(1).key()).isEqualTo(ActionItemText.key("file form i-765"));
        assertThat(rows.get(1).description()).isNull();
        assertThat(rows.get(2).title()).hasSize(118).endsWith("…").startsWith(longAction.substring(0, 117));
        assertThat(rows.get(2).description()).isEqualTo(longAction);

        JsonNode page = list(owner, "");
        assertThat(page.path("totalElements").asLong()).isEqualTo(3);
        assertThat(page.path("size").asInt()).isEqualTo(50);
        JsonNode first = page.path("content").get(0);
        List<String> fields = new ArrayList<>();
        first.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id", "assessmentId", "policyId", "policyTitle", "title",
                "description", "priority", "status", "deadline", "urgent", "overdue", "completedAt",
                "dismissedAt", "createdAt", "updatedAt", "text");
        assertThat(first.path("assessmentId").asLong()).isEqualTo(assessment);
        assertThat(first.path("policyId").asLong()).isEqualTo(policy.getId());
        assertThat(first.path("policyTitle").asText()).isEqualTo(policy.getTitle());
        assertThat(first.path("deadline").asText()).isEqualTo("2027-01-15");

        mvc.perform(get("/api/v1/impacts/" + assessment).header("Authorization", token(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionItems.length()").value(3))
                .andExpect(jsonPath("$.actionItems[0].text").value("Renew your I-20"))
                .andExpect(jsonPath("$.actionItems[0].status").value("PENDING"))
                .andExpect(jsonPath("$.actionItems[2].text").value(longAction))
                .andExpect(jsonPath("$.actionItems[2].id").value(rows.get(2).id()));
    }

    @Test
    void unverifiedPublishedDraftsReceiveItemsWhileBlankAssessmentsDoNot() throws Exception {
        User owner = account(UserRole.APPLICANT);
        recommendations.getOrGenerate(owner.getEmail(), policy(null),
                () -> rejected(List.of("Draft step that must stay hidden")));
        long held = jdbc.queryForObject("SELECT id FROM policy_recommendations WHERE user_id = ?",
                Long.class, owner.getId());
        actionItems.syncForAssessment(held);
        long none = publish(owner, policy(null), "HIGH", List.of());
        long blank = publish(owner, policy(null), "LOW", List.of("   ", "\t\n"));

        assertThat(rows(held)).extracting(Row::title, Row::priority).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Draft step that must stay hidden", "MEDIUM"));
        assertThat(rows(none)).isEmpty();
        assertThat(rows(blank)).isEmpty();
        assertThat(list(owner, "").path("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void regeneratingAHeldDraftAsApprovedPublishesItsChecklist() throws Exception {
        User owner = account(UserRole.APPLICANT);
        PolicyDocument policy = policy(null);
        recommendations.getOrGenerate(owner.getEmail(), policy, () -> rejected(List.of("Check the rule")));
        recommendations.getOrGenerate(owner.getEmail(), policy,
                () -> approved(ImpactUrgency.MEDIUM, List.of("Check the rule")));
        // The same fingerprint, so the upsert updated the held row instead of adding one.
        long assessment = jdbc.queryForObject("SELECT id FROM policy_recommendations WHERE user_id = ?",
                Long.class, owner.getId());

        assertThat(rows(assessment)).extracting(Row::title, Row::priority).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Check the rule", "MEDIUM"));
    }

    @Test
    void ownersSeeOnlyTheirItemsAndGet403ForSomeoneElses() throws Exception {
        User owner = account(UserRole.APPLICANT);
        User stranger = account(UserRole.APPLICANT);
        long mine = publish(owner, policy(null), "MEDIUM", List.of("Owner step one", "Owner step two"));
        long theirs = publish(stranger, policy(null), "MEDIUM", List.of("Stranger step"));
        long ownerItem = rows(mine).getFirst().id();

        assertThat(titles(list(owner, ""))).containsExactly("Owner step one", "Owner step two");
        assertThat(titles(list(stranger, ""))).containsExactly("Stranger step");

        update(stranger, ownerItem, "COMPLETED").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This action item belongs to another user"));
        assertThat(rows(mine).getFirst().status()).isEqualTo("PENDING");
        update(owner, rows(theirs).getFirst().id(), "DISMISSED").andExpect(status().isForbidden());
        update(owner, ownerItem, "COMPLETED").andExpect(status().isOk());
        update(owner, Long.MAX_VALUE, "COMPLETED").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Action item not found"));
        // The impact feed itself still answers 404 for another user's assessment.
        mvc.perform(get("/api/v1/impacts/" + mine).header("Authorization", token(stranger)))
                .andExpect(status().isNotFound());
    }

    @Test
    void itemsOfAHiddenAssessmentAreNotFoundForEveryone() throws Exception {
        User owner = account(UserRole.APPLICANT);
        User stranger = account(UserRole.APPLICANT);
        long assessment = publish(owner, policy(null), "LOW", List.of("Soon hidden"));
        long item = rows(assessment).getFirst().id();
        // A new version, as V29's revision trigger records every change of publication status.
        jdbc.update("UPDATE policy_recommendations SET publication_status = 'PENDING_REVIEW', "
                + "version = version + 1 WHERE id = ?", assessment);

        assertThat(list(owner, "").path("totalElements").asLong()).isZero();
        update(owner, item, "COMPLETED").andExpect(status().isNotFound());
        update(stranger, item, "COMPLETED").andExpect(status().isNotFound());
        assertThat(rows(assessment)).extracting(Row::status).containsExactly("PENDING");
    }

    @Test
    void listIsOrderedByDeadlineThenPriorityAndFilteredByStatusAndPriority() throws Exception {
        User owner = account(UserRole.APPLICANT);
        LocalDate later = LocalDate.of(2027, 3, 1);
        long low = publish(owner, policy(later), "LOW", List.of("Low one", "Low two"));
        long critical = publish(owner, policy(later), "CRITICAL", List.of("Critical one"));
        publish(owner, policy(null), "MEDIUM", List.of("Undated"));
        publish(owner, policy(LocalDate.of(2027, 2, 1)), "INFO", List.of("Sooner"));
        publish(owner, policy(later), "LOW", List.of("Second low assessment"));

        assertThat(titles(list(owner, ""))).containsExactly("Sooner", "Critical one", "Low one", "Low two",
                "Second low assessment", "Undated");
        JsonNode second = list(owner, "?size=2&page=1");
        assertThat(titles(second)).containsExactly("Low one", "Low two");
        assertThat(second.path("totalElements").asLong()).isEqualTo(6);
        assertThat(second.path("totalPages").asInt()).isEqualTo(3);
        assertThat(titles(list(owner, "?priority=LOW"))).containsExactly("Sooner", "Low one", "Low two",
                "Second low assessment");
        assertThat(titles(list(owner, "?priority=HIGH&priority=MEDIUM"))).containsExactly("Critical one", "Undated");

        assertThat(titles(list(owner, "?status=COMPLETED&status=DISMISSED"))).isEmpty();
        update(owner, rows(low).getFirst().id(), "COMPLETED").andExpect(status().isOk());
        update(owner, rows(critical).getFirst().id(), "DISMISSED").andExpect(status().isOk());
        update(owner, rows(low).get(1).id(), "IN_PROGRESS").andExpect(status().isOk());
        assertThat(titles(list(owner, "?status=COMPLETED&status=DISMISSED"))).containsExactly("Critical one", "Low one");
        assertThat(titles(list(owner, "?status=COMPLETED&priority=LOW"))).containsExactly("Low one");
        assertThat(titles(list(owner, "?status=IN_PROGRESS"))).containsExactly("Low two");
        assertThat(titles(list(owner, ""))).containsExactly("Sooner", "Low two", "Second low assessment", "Undated");

        for (String invalid : new String[] {"?status=DONE", "?priority=URGENT", "?size=101"}) {
            mvc.perform(get("/api/v1/actions" + invalid).header("Authorization", token(owner)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void everyTransitionStampsAndClearsItsTimestamps() throws Exception {
        User owner = account(UserRole.APPLICANT);
        long item = rows(publish(owner, policy(null), "MEDIUM", List.of("Move me around"))).getFirst().id();

        update(owner, item, "IN_PROGRESS").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.completedAt").isEmpty()).andExpect(jsonPath("$.dismissedAt").isEmpty());
        update(owner, item, "COMPLETED").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty()).andExpect(jsonPath("$.dismissedAt").isEmpty());

        // Each test is one transaction, so CURRENT_TIMESTAMP never moves: age the stamps instead.
        jdbc.update("UPDATE action_items SET updated_at = updated_at - interval '1 day', "
                + "completed_at = completed_at - interval '1 day' WHERE id = ?", item);
        Row aged = row(item);
        update(owner, item, "COMPLETED").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(row(item)).isEqualTo(aged);

        update(owner, item, "DISMISSED").andExpect(status().isOk())
                .andExpect(jsonPath("$.completedAt").isEmpty()).andExpect(jsonPath("$.dismissedAt").isNotEmpty());
        assertThat(row(item).updatedAt()).isAfter(aged.updatedAt());
        update(owner, item, "PENDING").andExpect(status().isOk())
                .andExpect(jsonPath("$.completedAt").isEmpty()).andExpect(jsonPath("$.dismissedAt").isEmpty());
        update(owner, item, "DISMISSED").andExpect(status().isOk());
        update(owner, item, "COMPLETED").andExpect(status().isOk())
                .andExpect(jsonPath("$.completedAt").isNotEmpty()).andExpect(jsonPath("$.dismissedAt").isEmpty());
        update(owner, item, "IN_PROGRESS").andExpect(status().isOk())
                .andExpect(jsonPath("$.completedAt").isEmpty()).andExpect(jsonPath("$.dismissedAt").isEmpty());
        assertThat(row(item).status()).isEqualTo("IN_PROGRESS");

        for (String body : new String[] {"{\"status\":\"DONE\"}", "{}", "{\"status\":null}"}) {
            mvc.perform(patch("/api/v1/actions/" + item).header("Authorization", token(owner))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void urgentAndOverdueFollowTheOwnersToday() throws Exception {
        User owner = account(UserRole.APPLICANT);
        LocalDate today = clock.today(owner.getId());
        long overdue = publish(owner, policy(today.minusDays(1)), "LOW", List.of("Yesterday"));
        publish(owner, policy(today), "LOW", List.of("Today"));
        publish(owner, policy(today.plusDays(7)), "LOW", List.of("In seven days"));
        publish(owner, policy(today.plusDays(8)), "LOW", List.of("In eight days"));
        publish(owner, policy(null), "LOW", List.of("Undated"));

        Map<String, JsonNode> items = byTitle(list(owner, ""));
        assertFlags(items.get("Yesterday"), true, true);
        assertFlags(items.get("Today"), true, false);
        assertFlags(items.get("In seven days"), true, false);
        assertFlags(items.get("In eight days"), false, false);
        assertFlags(items.get("Undated"), false, false);

        update(owner, rows(overdue).getFirst().id(), "COMPLETED").andExpect(status().isOk())
                .andExpect(jsonPath("$.urgent").value(false)).andExpect(jsonPath("$.overdue").value(false));

        if (clock.isEnabled()) {
            clock.setUserDate(owner.getId(), today.plusDays(8));
            try {
                Map<String, JsonNode> later = byTitle(list(owner, ""));
                assertFlags(later.get("In seven days"), true, true);
                assertFlags(later.get("In eight days"), true, false);
            } finally {
                clock.setUserDate(owner.getId(), null);
            }
        }
    }

    @Test
    void completingOrDismissingLeavesTheActiveListButKeepsTheRecord() throws Exception {
        User owner = account(UserRole.APPLICANT);
        long assessment = publish(owner, policy(null), "HIGH", List.of("Finish me", "Skip me", "Keep me"));
        List<Row> rows = rows(assessment);
        update(owner, rows.get(0).id(), "COMPLETED").andExpect(status().isOk());
        update(owner, rows.get(1).id(), "DISMISSED").andExpect(status().isOk());

        assertThat(titles(list(owner, ""))).containsExactly("Keep me");
        assertThat(titles(list(owner, "?status=COMPLETED"))).containsExactly("Finish me");
        assertThat(titles(list(owner, "?status=DISMISSED"))).containsExactly("Skip me");
        assertThat(rows(assessment)).extracting(Row::status).containsExactly("COMPLETED", "DISMISSED", "PENDING");
        // The impact detail still lists all three, whatever their status.
        mvc.perform(get("/api/v1/impacts/" + assessment).header("Authorization", token(owner)))
                .andExpect(jsonPath("$.actionItems[*].status").value(
                        org.hamcrest.Matchers.contains("COMPLETED", "DISMISSED", "PENDING")));
    }

    @Test
    void itemsOfArchivedAssessmentsAreKeptButLeftOutOfTheActiveList() throws Exception {
        User owner = account(UserRole.APPLICANT);
        long archived = publish(owner, policy(null), "MEDIUM", List.of("From the archived assessment"));
        publish(owner, policy(null), "MEDIUM", List.of("From the current assessment"));
        mvc.perform(patch("/api/v1/impacts/" + archived).header("Authorization", token(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"archived\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionItems[0].title").value("From the archived assessment"));

        assertThat(titles(list(owner, ""))).containsExactly("From the current assessment");
        assertThat(titles(list(owner, "?status=PENDING")))
                .containsExactlyInAnyOrder("From the archived assessment", "From the current assessment");
        assertThat(rows(archived)).hasSize(1);
    }

    @Test
    void aCorrectionKeepsProgressDropsUntouchedItemsAndAddsNewOnes() throws Exception {
        User owner = account(UserRole.APPLICANT);
        User manager = account(UserRole.MANAGER);
        link(manager, owner);
        recommendations.getOrGenerate(owner.getEmail(), policy(LocalDate.of(2027, 1, 15)),
                () -> approved(ImpactUrgency.HIGH, List.of("Keep your passport valid",
                        "Book a biometrics appointment", "Ask your DSO about travel", "Update your address",
                        "Pay the fee")));
        long assessment = jdbc.queryForObject("SELECT id FROM policy_recommendations WHERE user_id = ?",
                Long.class, owner.getId());
        Map<String, Row> before = rows(assessment).stream()
                .collect(Collectors.toMap(Row::title, Function.identity()));
        update(owner, before.get("Keep your passport valid").id(), "DISMISSED").andExpect(status().isOk());
        update(owner, before.get("Book a biometrics appointment").id(), "IN_PROGRESS").andExpect(status().isOk());
        update(owner, before.get("Pay the fee").id(), "COMPLETED").andExpect(status().isOk());

        // The dismissed action is restated with different case and spacing; the started, completed
        // and untouched "Ask your DSO" items are dropped; one action is new.
        String correction = """
                {"severity":"MEDIUM","confidence":85,"summary":"Corrected by a manager",
                 "analysis":["The rule applies"],"missingInformation":[],
                 "actionItems":["keep  your PASSPORT valid","Update your address","Upload a new photo"]}
                """;
        for (int i = 0; i < 2; i++) {
            mvc.perform(patch("/api/v1/review/impacts/" + assessment).header("Authorization", token(manager))
                            .contentType(MediaType.APPLICATION_JSON).content(correction))
                    .andExpect(status().isOk());
        }

        Map<String, Row> after = rows(assessment).stream()
                .collect(Collectors.toMap(Row::title, Function.identity()));
        assertThat(after).containsOnlyKeys("Keep your passport valid", "Book a biometrics appointment",
                "Update your address", "Pay the fee", "Upload a new photo");
        assertThat(after.get("Keep your passport valid").id()).isEqualTo(before.get("Keep your passport valid").id());
        assertThat(after.get("Keep your passport valid").status()).isEqualTo("DISMISSED");
        assertThat(after.get("Book a biometrics appointment").status()).isEqualTo("IN_PROGRESS");
        assertThat(after.get("Pay the fee").status()).isEqualTo("COMPLETED");
        assertThat(after.get("Update your address").id()).isEqualTo(before.get("Update your address").id());
        // Kept items are not recomputed; only the new item takes the corrected severity.
        assertThat(after.get("Update your address").priority()).isEqualTo("HIGH");
        assertThat(after.get("Upload a new photo")).satisfies(row -> {
            assertThat(row.status()).isEqualTo("PENDING");
            assertThat(row.priority()).isEqualTo("MEDIUM");
            assertThat(row.position()).isEqualTo(3);
            assertThat(row.deadline()).isEqualTo(LocalDate.of(2027, 1, 15));
        });
        assertThat(titles(list(owner, ""))).containsExactlyInAnyOrder("Book a biometrics appointment",
                "Update your address", "Upload a new photo");
    }

    @Test
    void aCorrectionPublishesTheChecklistOfAHeldDraft() throws Exception {
        User owner = account(UserRole.APPLICANT);
        User manager = account(UserRole.MANAGER);
        link(manager, owner);
        recommendations.getOrGenerate(owner.getEmail(), policy(null), () -> rejected(List.of("Draft step")));
        long assessment = jdbc.queryForObject("SELECT id FROM policy_recommendations WHERE user_id = ?",
                Long.class, owner.getId());
        assertThat(rows(assessment)).extracting(Row::title).containsExactly("Draft step");

        mvc.perform(patch("/api/v1/review/impacts/" + assessment).header("Authorization", token(manager))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"severity":"LOW","confidence":80,"summary":"Reviewed","analysis":[],
                                 "missingInformation":[],"actionItems":["Reviewed step"]}
                                """))
                .andExpect(status().isOk());

        assertThat(rows(assessment)).extracting(Row::title, Row::priority).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Reviewed step", "LOW"));
    }

    private void assertFlags(JsonNode item, boolean urgent, boolean overdue) {
        assertThat(item).as("item").isNotNull();
        assertThat(item.path("urgent").asBoolean()).as(item.path("title").asText() + " urgent").isEqualTo(urgent);
        assertThat(item.path("overdue").asBoolean()).as(item.path("title").asText() + " overdue").isEqualTo(overdue);
    }

    private JsonNode list(User user, String query) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/v1/actions" + query).header("Authorization", token(user)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private List<String> titles(JsonNode page) {
        List<String> titles = new ArrayList<>();
        page.path("content").forEach(item -> titles.add(item.path("title").asText()));
        return titles;
    }

    private Map<String, JsonNode> byTitle(JsonNode page) {
        Map<String, JsonNode> items = new java.util.HashMap<>();
        page.path("content").forEach(item -> items.put(item.path("title").asText(), item));
        return items;
    }

    private ResultActions update(User user, long id, String status) throws Exception {
        return mvc.perform(patch("/api/v1/actions/" + id).header("Authorization", token(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"));
    }

    private User account(UserRole role) {
        User user = new User("Checklist test", "actions-" + UUID.randomUUID() + "@example.org",
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

    private PolicyDocument policy(LocalDate effectiveDate) {
        return policies.saveAndFlush(new PolicyDocument("actions-" + UUID.randomUUID(),
                "Student status rule", "DHS", "F-1", PolicyStatus.FINAL, SourceType.PRIMARY,
                LocalDate.of(2026, 10, 1), effectiveDate, "https://www.federalregister.gov/example",
                "Official source text", "hash"));
    }

    /** A published, judge-approved assessment saved directly, with its checklist generated. */
    private long publish(User owner, PolicyDocument policy, String severity, List<String> actions) throws Exception {
        long id = jdbc.queryForObject("""
                INSERT INTO policy_recommendations
                    (user_id, policy_id, input_hash, assessment_json, publication_status, severity)
                VALUES (?, ?, ?, ?, 'PUBLISHED', ?) RETURNING id
                """, Long.class, owner.getId(), policy.getId(), UUID.randomUUID().toString(),
                mapper.writeValueAsString(approved(ImpactUrgency.MEDIUM, actions)), severity);
        actionItems.syncForAssessment(id);
        return id;
    }

    private ProfileImpactAssessment approved(ImpactUrgency urgency, List<String> actions) {
        return new ProfileImpactAssessment(urgency, 90, "Approved summary", List.of("Source matches profile"),
                actions, List.of(), false, "OpenAI", "test",
                new JudgeEvaluation(JudgeEvaluation.Verdict.YES, "Supported", "test"), 1);
    }

    private ProfileImpactAssessment rejected(List<String> actions) {
        return new ProfileImpactAssessment(ImpactUrgency.MEDIUM, 70, "Draft summary", List.of("Draft reason"),
                actions, List.of(), true, "OpenAI", "test",
                new JudgeEvaluation(JudgeEvaluation.Verdict.NO, "Needs correction", "test"), 1);
    }

    private List<Row> rows(long assessmentId) {
        return jdbc.query("SELECT * FROM action_items WHERE assessment_id = ? ORDER BY position, id",
                (rs, n) -> Row.of(rs), assessmentId);
    }

    private Row row(long id) {
        return jdbc.queryForObject("SELECT * FROM action_items WHERE id = ?", (rs, n) -> Row.of(rs), id);
    }

    record Row(long id, long assessmentId, long userId, String key, int position, String title,
            String description, String priority, String status, LocalDate deadline, Instant completedAt,
            Instant dismissedAt, Instant createdAt, Instant updatedAt) {
        static Row of(java.sql.ResultSet rs) throws java.sql.SQLException {
            return new Row(rs.getLong("id"), rs.getLong("assessment_id"), rs.getLong("user_id"),
                    rs.getString("item_key"), rs.getInt("position"), rs.getString("title"),
                    rs.getString("description"), rs.getString("priority"), rs.getString("status"),
                    rs.getObject("deadline", LocalDate.class), instant(rs, "completed_at"),
                    instant(rs, "dismissed_at"), instant(rs, "created_at"), instant(rs, "updated_at"));
        }

        private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
            var timestamp = rs.getTimestamp(column);
            return timestamp == null ? null : timestamp.toInstant();
        }
    }
}
