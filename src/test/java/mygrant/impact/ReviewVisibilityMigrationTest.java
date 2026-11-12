package mygrant.impact;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import mygrant.support.PgvectorIntegrationTestBase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ReviewVisibilityMigrationTest extends PgvectorIntegrationTestBase {
    @Test
    void backfillsModelFlagsLowConfidenceAndHumanApprovalWithoutLosingActions() {
        String database = "review_" + UUID.randomUUID().toString().replace("-", "");
        var admin = jdbc(POSTGRES.getJdbcUrl());
        admin.execute("CREATE DATABASE " + database);
        String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database);
        try {
            Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).target("44").load().migrate();
            var jdbc = jdbc(url);
            Long owner = jdbc.queryForObject("INSERT INTO users (full_name,email,password_hash) VALUES ('Test','test@example.org','unused') RETURNING id", Long.class);
            Long policy = jdbc.queryForObject("""
                    INSERT INTO policy_documents (external_id,title,agency,visa_type,status,source_type,
                        publication_date,source_url,content,content_hash)
                    VALUES ('test','Rule','DHS','F-1','FINAL','PRIMARY',CURRENT_DATE,'https://example.org','text','hash') RETURNING id
                    """, Long.class);
            for (String input : new String[] {"model-flag", "low-confidence", "approved", "not-required"}) {
                String json = """
                        {"urgency":"MEDIUM","status":"COMPLETED","confidence":%d,"summary":"Saved result",
                         "requiresHumanReview":%s,"recommendedActions":["Read the source"]}
                        """.formatted(input.equals("low-confidence") || input.equals("approved") ? 20 : 90,
                                input.equals("model-flag") || input.equals("approved"));
                Long id = jdbc.queryForObject("""
                        INSERT INTO policy_recommendations (user_id,policy_id,input_hash,assessment_json,publication_status,
                            severity,reviewed_by_human) VALUES (?,?,?,?,'PUBLISHED','MEDIUM',?) RETURNING id
                        """, Long.class, owner, policy, input, json, input.equals("approved"));
                jdbc.update("""
                        INSERT INTO action_items (assessment_id,user_id,item_key,position,title,priority)
                        VALUES (?,?,'test-key',1,'Read the source','MEDIUM')
                        """, id, owner);
            }
            Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
            for (String input : new String[] {"model-flag", "low-confidence", "approved", "not-required"}) {
                var row = jdbc.queryForMap("SELECT review_status,requires_review,publication_status,version FROM policy_recommendations WHERE input_hash = ?", input);
                boolean pending = input.equals("model-flag") || input.equals("low-confidence");
                assertThat(row.get("review_status")).isEqualTo(pending ? "PENDING_REVIEW"
                        : input.equals("approved") ? "APPROVED" : "NOT_REQUIRED");
                assertThat(row.get("publication_status")).isEqualTo(pending ? "PENDING_REVIEW" : "PUBLISHED");
                assertThat(row.get("version")).isEqualTo(pending ? 2 : 1);
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM action_items", Integer.class)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM policy_recommendations r WHERE " + ImpactVisibility.VISIBLE, Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM impact_assessment_revisions", Integer.class)).isEqualTo(6);
        } finally {
            admin.execute("DROP DATABASE " + database + " WITH (FORCE)");
        }
    }

    private JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));
    }
}
