package mygrant.ingestion.visabulletin;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class VisaBulletinStore {
    private final JdbcTemplate jdbc;

    public VisaBulletinStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Saves every chart and marks health OK in one transaction. */
    @Transactional
    public int save(VisaBulletinParser.Bulletin bulletin) {
        Date month = Date.valueOf(bulletin.publicationMonth().atDay(1));
        Timestamp now = Timestamp.from(Instant.now());
        for (VisaBulletinParser.Entry entry : bulletin.entries()) {
            jdbc.update("""
                    INSERT INTO visa_bulletin_entries
                    (publication_month, preference_type, chart_type, category, chargeability_area,
                     cutoff_kind, cutoff_date, source_url, imported_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (publication_month, preference_type, chart_type, category, chargeability_area)
                    DO UPDATE SET cutoff_kind = EXCLUDED.cutoff_kind,
                                  cutoff_date = EXCLUDED.cutoff_date,
                                  source_url = EXCLUDED.source_url,
                                  imported_at = EXCLUDED.imported_at
                    """, month, entry.preference(), entry.chart(), entry.category(),
                    entry.chargeabilityArea(), entry.cutoffKind(),
                    entry.cutoffDate() == null ? null : Date.valueOf(entry.cutoffDate()),
                    bulletin.sourceUrl(), now);
        }
        jdbc.update("""
                UPDATE visa_bulletin_health
                SET status = 'OK', last_attempt_at = ?, last_success_at = ?,
                    publication_month = ?, source_url = ?, error_message = NULL
                WHERE source_name = 'VISA_BULLETIN'
                """, now, now, month, bulletin.sourceUrl());
        return bulletin.entries().size();
    }

    /** Called after a download, layout, or database failure; prior good rows remain. */
    @Transactional
    public void fail(String error) {
        String message = error == null ? "Unknown import error" : error;
        jdbc.update("""
                UPDATE visa_bulletin_health
                SET status = 'FAILED', last_attempt_at = ?, error_message = ?
                WHERE source_name = 'VISA_BULLETIN'
                """, Timestamp.from(Instant.now()), message.substring(0, Math.min(message.length(), 1000)));
    }

    /** Returns one saved month for the admin inspection table. */
    public List<EntryView> entries(YearMonth month) {
        return jdbc.query("""
                SELECT preference_type, chart_type, category, chargeability_area,
                       cutoff_kind, cutoff_date, source_url
                FROM visa_bulletin_entries
                WHERE publication_month = ?
                ORDER BY preference_type, chart_type, category, chargeability_area
                """, (rs, row) -> {
            Date cutoff = rs.getDate("cutoff_date");
            return new EntryView(rs.getString("preference_type"),
                    rs.getString("chart_type"), rs.getString("category"),
                    rs.getString("chargeability_area"), rs.getString("cutoff_kind"),
                    cutoff == null ? null : cutoff.toLocalDate(), rs.getString("source_url"));
        }, Date.valueOf(month.atDay(1)));
    }

    public Health health() {
        return jdbc.queryForObject("""
                SELECT status, last_attempt_at, last_success_at, publication_month,
                       source_url, error_message
                FROM visa_bulletin_health WHERE source_name = 'VISA_BULLETIN'
                """, (rs, row) -> {
            Timestamp attempted = rs.getTimestamp("last_attempt_at");
            Timestamp succeeded = rs.getTimestamp("last_success_at");
            Date month = rs.getDate("publication_month");
            return new Health(rs.getString("status"),
                    attempted == null ? null : attempted.toInstant(),
                    succeeded == null ? null : succeeded.toInstant(),
                    month == null ? null : YearMonth.from(month.toLocalDate()),
                    rs.getString("source_url"), rs.getString("error_message"));
        });
    }

    public record EntryView(String preferenceType, String chartType, String category,
            String chargeabilityArea, String cutoffKind, LocalDate cutoffDate,
            String sourceUrl) {}

    public record Health(String status, Instant lastAttemptAt, Instant lastSuccessAt,
            YearMonth publicationMonth, String sourceUrl, String errorMessage) {}
}
