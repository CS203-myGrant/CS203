package mygrant.actions;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import mygrant.impact.ImpactUrgency;
import mygrant.impact.JudgeEvaluation;
import mygrant.impact.ProfileImpactAssessment;
import mygrant.policies.PolicyDocument;
import mygrant.policies.PolicyDocumentRepository;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;
import mygrant.user.User;
import mygrant.user.UserRepository;

/**
 * The V30 backfill and ActionItemService must turn the same actions into the same items, or a
 * backfilled item would be treated as a different action when its assessment is next revised.
 * Runs the migration's own INSERT statement again inside the rolled-back test transaction.
 */
@SpringBootTest
@Transactional
class ActionItemBackfillParityTest {

    private static final List<String> AWKWARD = List.of(
            "  Renew   your I-20  ",
            "\tFile\tForm I-765\n\nbefore the\u000Bdeadline\r\n",
            "ConTact Your DSO",
            // Normalises like the previous action, so only the first becomes an item.
            "contact  your\tdso",
            "Prüfen Sie die ÉTUDIANT-Unterlagen – Ñandú Café",
            "Подайте ДОКУМЕНТЫ вовремя",
            // Ideographic and no-break spaces are not whitespace in either rule.
            "签证　更新 提醒",
            "Upload the 📄 scan TODAY",
            "   ",
            "a".repeat(116) + "😀😀😀 and the rest of an action long enough to be shortened to a title");

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired PolicyDocumentRepository policies;
    @Autowired ActionItemService actionItems;

    @Test
    void javaRulesMatchTheV30BackfillForAwkwardText() throws Exception {
        User owner = users.saveAndFlush(new User("Parity test", "parity-" + UUID.randomUUID() + "@example.org",
                "unused"));
        PolicyDocument policy = policies.saveAndFlush(new PolicyDocument("parity-" + UUID.randomUUID(),
                "Parity rule", "DHS", "F-1", PolicyStatus.FINAL, SourceType.PRIMARY, LocalDate.of(2026, 10, 1),
                LocalDate.of(2027, 2, 1), "https://www.federalregister.gov/example", "Text", "hash"));
        String json = mapper.writeValueAsString(new ProfileImpactAssessment(ImpactUrgency.HIGH, 90, "Summary",
                List.of(), AWKWARD, List.of(), false, "OpenAI", "test",
                new JudgeEvaluation(JudgeEvaluation.Verdict.YES, "Supported", "test"), 1));

        long backfilled = insert(owner, policy, json);
        jdbc.update(backfillStatement());
        long generated = insert(owner, policy, json);
        actionItems.syncForAssessment(generated);

        List<Item> fromSql = items(backfilled);
        assertThat(fromSql).hasSize(AWKWARD.size() - 2).isEqualTo(items(generated));
        for (Item item : fromSql) {
            String action = AWKWARD.get(item.position() - 1);
            assertThat(item.key()).as(action).isEqualTo(ActionItemText.key(action));
            assertThat(item.title()).as(action).isEqualTo(ActionItemText.title(action));
            assertThat(item.description()).as(action).isEqualTo(ActionItemText.description(action));
        }
        assertThat(fromSql).extracting(Item::position).containsExactly(1, 2, 3, 5, 6, 7, 8, 10);
        assertThat(fromSql.getLast().title()).hasSize(119).endsWith("😀…");
    }

    /** The last statement of V30, exactly as Flyway ran it. */
    private String backfillStatement() throws Exception {
        String migration = new ClassPathResource("db/migration/V30__create_action_items.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        return migration.substring(migration.lastIndexOf("INSERT INTO action_items"));
    }

    private long insert(User owner, PolicyDocument policy, String json) {
        return jdbc.queryForObject("""
                INSERT INTO policy_recommendations
                    (user_id, policy_id, input_hash, assessment_json, publication_status, severity)
                VALUES (?, ?, ?, ?, 'PUBLISHED', 'MEDIUM') RETURNING id
                """, Long.class, owner.getId(), policy.getId(), UUID.randomUUID().toString(), json);
    }

    private List<Item> items(long assessmentId) {
        return jdbc.query("""
                SELECT item_key, position, title, description, priority, status, deadline
                FROM action_items WHERE assessment_id = ? ORDER BY position
                """, (rs, row) -> new Item(rs.getString("item_key"), rs.getInt("position"), rs.getString("title"),
                rs.getString("description"), rs.getString("priority"), rs.getString("status"),
                rs.getObject("deadline", LocalDate.class)), assessmentId);
    }

    private record Item(String key, int position, String title, String description, String priority,
            String status, LocalDate deadline) {}
}
