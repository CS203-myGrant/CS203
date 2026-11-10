package mygrant.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.UUID;

import mygrant.support.PgvectorIntegrationTestBase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Migrate real pre-V44 rows, in a disposable database separate from Spring test fixtures. */
class SeverityMigrationTest extends PgvectorIntegrationTestBase {
    @Test
    void convertsStoredLabelsAndRevisionsWithoutChangingReviewStateOrHashedCandidates() throws Exception {
        String database = "severity_" + UUID.randomUUID().toString().replace("-", "");
        var admin = jdbc(POSTGRES.getJdbcUrl());
        admin.execute("CREATE DATABASE " + database);
        String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database);
        try {
            Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                    .target("43").load().migrate();
            var jdbc = jdbc(url);
            Long owner = jdbc.queryForObject("""
                    INSERT INTO users (full_name, email, password_hash) VALUES ('Test', 'test@example.org', 'unused')
                    RETURNING id
                    """, Long.class);
            Long policy = jdbc.queryForObject("""
                    INSERT INTO policy_documents (external_id, title, agency, visa_type, status, source_type,
                        publication_date, source_url, content, content_hash)
                    VALUES ('test', 'Rule', 'DHS', 'F-1', 'FINAL', 'PRIMARY', CURRENT_DATE, 'https://example.org', 'text', 'hash')
                    RETURNING id
                    """, Long.class);
            var mapping = new LinkedHashMap<String, String>();
            mapping.put("CRITICAL", "HIGH"); mapping.put("HIGH", "HIGH");
            mapping.put("WARNING", "MEDIUM"); mapping.put("MEDIUM", "MEDIUM");
            mapping.put("INFORMATIONAL", "LOW"); mapping.put("LOW", "LOW");
            mapping.put("NO_IMPACT", "NOT_APPLICABLE"); mapping.put("INFO", "NOT_APPLICABLE");
            mapping.put("NOT_APPLICABLE", "NOT_APPLICABLE"); mapping.put("UNAVAILABLE", null);
            for (var entry : mapping.entrySet()) {
                String json = """
                        {"urgency":"%s","confidence":50,"summary":"Original summary",
                         "requiresHumanReview":true,"reasons":[],"recommendedActions":[],"missingInformation":[]}
                        """.formatted(entry.getKey());
                jdbc.update("""
                        INSERT INTO policy_recommendations
                            (user_id, policy_id, input_hash, assessment_json, publication_status, severity)
                        VALUES (?, ?, ?, ?, 'PENDING_REVIEW', 'INFO')
                        """, owner, policy, entry.getKey(), json);
            }
            // Preserve exact raw model snapshots, which are content-addressed evaluation evidence.
            String rawCandidate = "{\"urgency\":\"HIGH\",\"summary\":\"Original model output\"}";
            UUID auditId = UUID.randomUUID();
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(rawCandidate.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            jdbc.update("""
                    INSERT INTO ai_output_evaluations
                        (id, output_type, output_version, verdict, model, prompt_version, evaluated_at)
                    VALUES (?, 'ASSESSMENT', ?, 'NO', 'test', 'test', CURRENT_TIMESTAMP)
                    """, auditId, hash);
            jdbc.update("""
                    INSERT INTO stored_ai_outputs
                        (id, output_type, candidate_json, evidence_context, citations_json, latest_evaluation_json)
                    VALUES (?, 'ASSESSMENT', ?, 'Evidence', '[]', '{}')
                    """, auditId, rawCandidate);
            Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
            for (var entry : mapping.entrySet()) {
                var row = jdbc.queryForMap("""
                        SELECT severity, assessment_json::jsonb ->> 'urgency' AS urgency,
                               assessment_json::jsonb ->> 'status' AS status, publication_status,
                               assessment_json::jsonb ->> 'requiresHumanReview' AS review, version
                        FROM policy_recommendations WHERE input_hash = ?
                        """, entry.getKey());
                assertThat(row.get("severity")).isEqualTo(entry.getValue());
                assertThat(row.get("urgency")).isEqualTo(entry.getValue());
                assertThat(row.get("status")).isEqualTo(entry.getValue() == null ? "UNAVAILABLE" : "COMPLETED");
                assertThat(row.get("publication_status")).isEqualTo("PENDING_REVIEW");
                assertThat(row.get("review")).isEqualTo("true");
                assertThat(row.get("version")).isEqualTo(1);
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM impact_assessment_revisions", Integer.class))
                    .isEqualTo(mapping.size());
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM impact_assessment_revisions
                    WHERE assessment_json::jsonb ->> 'urgency' IN ('CRITICAL','WARNING','INFORMATIONAL','NO_IMPACT','INFO','UNAVAILABLE')
                    """, Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT candidate_json FROM stored_ai_outputs WHERE id = ?", String.class, auditId))
                    .isEqualTo(rawCandidate);
            assertThat(jdbc.queryForObject("SELECT output_version FROM ai_output_evaluations WHERE id = ?", String.class, auditId))
                    .isEqualTo(hash);
            assertThatThrownBy(() -> jdbc.update("UPDATE policy_recommendations SET severity = 'CRITICAL'"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO impact_assessments (user_id, policy_id, profile_version, status, urgency,
                        confidence, summary) VALUES (?, ?, 1, 'COMPLETED', 'UNAVAILABLE', 90, 'Invalid')
                    """, owner, policy)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            admin.execute("DROP DATABASE " + database + " WITH (FORCE)");
        }
    }

    private JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));
    }
}
