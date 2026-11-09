package mygrant.ingestion.uscis;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import mygrant.ingestion.connector.ConnectorHealth;
import mygrant.ingestion.connector.SourceIdentity;

@Repository
public class UscisProcessingTimeStore {
    private static final String SOURCE = "USCIS_PROCESSING_TIMES";
    private final JdbcTemplate jdbc;

    public UscisProcessingTimeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** An observation is immutable; an identical capture returns its existing ID. */
    @Transactional
    public Saved save(UscisProcessingTimeParser.Observation observation) {
        List<Long> inserted = jdbc.query("""
                INSERT INTO processing_time_snapshots
                (source_name, form_type, form_category, office, observed_on,
                 percentile, percentile_value, median_value, duration_unit,
                 source_url, source_content_hash, source_excerpt, imported_at)
                VALUES (?, ?, ?, ?, ?, 80, ?, NULL, ?, ?, ?, ?, ?)
                ON CONFLICT (source_name, form_type, form_category, office, observed_on,
                             percentile, source_content_hash)
                DO NOTHING RETURNING id
                """, (rs, row) -> rs.getLong(1), SOURCE, observation.formType(),
                observation.formCategory(), observation.office(), Date.valueOf(observation.observedOn()),
                observation.percentileValue(), observation.durationUnit(), observation.sourceUrl(),
                observation.contentHash(), observation.sourceExcerpt(), Timestamp.from(Instant.now()));
        boolean duplicate = inserted.isEmpty();
        long id;
        if (duplicate) {
            id = jdbc.queryForObject("""
                    SELECT id
                    FROM processing_time_snapshots
                    WHERE source_name = ? AND form_type = ? AND form_category = ?
                      AND office = ? AND observed_on = ? AND percentile = 80
                      AND source_content_hash = ?
                    """, Long.class,
                    SOURCE, observation.formType(), observation.formCategory(), observation.office(),
                    Date.valueOf(observation.observedOn()), observation.contentHash());
        } else {
            id = inserted.get(0);
        }
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                UPDATE uscis_processing_time_health
                SET status = 'OK', last_attempt_at = ?, last_success_at = ?,
                    error_stage = NULL, error_message = NULL
                WHERE source_name = ?
                """, now, now, SOURCE);
        return new Saved(id, duplicate);
    }

    @Transactional
    public void fail(ConnectorHealth.Stage stage, String error) {
        String message = error == null || error.isBlank() ? "Unknown import error" : error;
        jdbc.update("""
                UPDATE uscis_processing_time_health
                SET status = 'FAILED', last_attempt_at = ?, error_stage = ?, error_message = ?
                WHERE source_name = ?
                """, Timestamp.from(Instant.now()), stage.name(),
                message.substring(0, Math.min(message.length(), 1000)), SOURCE);
    }

    public ConnectorHealth health(SourceIdentity source) {
        return jdbc.queryForObject("""
                SELECT status, last_attempt_at, last_success_at, error_stage, error_message
                FROM uscis_processing_time_health WHERE source_name = ?
                """, (rs, row) -> {
            Timestamp attempt = rs.getTimestamp("last_attempt_at");
            Timestamp success = rs.getTimestamp("last_success_at");
            String stage = rs.getString("error_stage");
            return new ConnectorHealth(source, ConnectorHealth.Status.valueOf(rs.getString("status")),
                    attempt == null ? null : attempt.toInstant(),
                    success == null ? null : success.toInstant(),
                    stage == null ? null : ConnectorHealth.Stage.valueOf(stage),
                    rs.getString("error_message"));
        }, SOURCE);
    }

    public record Saved(long id, boolean duplicate) {}
}
