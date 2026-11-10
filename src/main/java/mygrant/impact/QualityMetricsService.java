package mygrant.impact;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Read-only projections of existing evaluation and review audits. Rates are fractions, not percentages. */
@Service
public class QualityMetricsService {
    private final JdbcTemplate jdbc;
    public QualityMetricsService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Metrics(LocalDate from, LocalDate to, String timezone,
            Instant startInclusive, Instant endExclusive, String countingUnit,
            long evaluatedVersions, long passedVersions, long failedVersions, long unavailableVersions,
            Double passRate, Double averageFaithfulness, long faithfulnessSampleSize,
            Double averageRelevance, long relevanceSampleSize,
            long pendingReviewAtEnd, long reviewQueueEntries,
            long managerReviewedOutputs, long comparableManagerReviews, long managerOverrides,
            Double managerOverrideRate) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Metrics metrics(LocalDate from, LocalDate to) {
        LocalDate last = to == null ? LocalDate.now(ZoneOffset.UTC) : to;
        LocalDate first = from == null ? last.minusDays(29) : from;
        if (last.isBefore(first) || last.equals(LocalDate.MAX)) {
            throw new IllegalArgumentException("from must be on or before to, and to must have a following day");
        }
        Instant start = first.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = last.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Timestamp startTime = Timestamp.from(start), endTime = Timestamp.from(end);

        // The content hash identifies a version. Identical content across owners
        // is intentionally one unit; no raw output/profile data is returned.
        Map<String, Object> evaluations = jdbc.queryForMap("""
                WITH versions AS (
                    SELECT DISTINCT ON (output_type, output_version) *
                    FROM ai_output_evaluations
                    WHERE evaluated_at >= ? AND evaluated_at < ?
                    ORDER BY output_type, output_version, evaluated_at DESC, id DESC
                )
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE verdict = 'YES') AS passed,
                       count(*) FILTER (WHERE verdict = 'NO') AS failed,
                       count(*) FILTER (WHERE verdict = 'UNAVAILABLE') AS unavailable,
                       avg(faithfulness) AS faithfulness, count(faithfulness) AS faithfulness_count,
                       avg(relevance) AS relevance, count(relevance) AS relevance_count
                FROM versions
                """, startTime, endTime);

        // Revisions retain queue state, so today's state cannot rewrite an
        // earlier report. Multiple queue entries of one review count once.
        Map<String, Object> queue = jdbc.queryForMap("""
                WITH history AS (
                    SELECT *,
                        lag(publication_status) OVER (
                            PARTITION BY assessment_id ORDER BY changed_at, version, id) AS previous_status,
                        row_number() OVER (
                            PARTITION BY assessment_id ORDER BY changed_at DESC, version DESC, id DESC) AS latest
                    FROM impact_assessment_revisions WHERE changed_at < ?
                )
                SELECT count(*) FILTER (WHERE latest = 1 AND publication_status = 'PENDING_REVIEW') AS pending,
                       count(DISTINCT assessment_id) FILTER (
                           WHERE changed_at >= ? AND publication_status = 'PENDING_REVIEW'
                             AND previous_status IS DISTINCT FROM 'PENDING_REVIEW') AS entered
                FROM history
                """, endTime, startTime);

        // Keep only each review's latest manager decision in the range. Compare
        // with the preceding machine verdict; a correction of a prior human
        // correction still compares with that preceding machine decision.
        Map<String, Object> reviews = jdbc.queryForMap("""
                WITH human AS (
                    SELECT DISTINCT ON (r.assessment_id) r.*
                    FROM impact_assessment_revisions r JOIN users u ON u.id = r.changed_by_user_id
                    WHERE u.role = 'MANAGER' AND r.assessment_json::jsonb #>> '{evaluation,model}' = 'Human'
                      AND r.changed_at >= ? AND r.changed_at < ?
                    ORDER BY r.assessment_id, r.changed_at DESC, r.version DESC, r.id DESC
                ), comparisons AS (
                    SELECT h.publication_status, a.verdict
                    FROM human h LEFT JOIN LATERAL (
                        SELECT r.assessment_json::jsonb #>> '{evaluation,verdict}' AS verdict
                        FROM impact_assessment_revisions r
                        WHERE r.assessment_id = h.assessment_id AND r.version < h.version
                          AND COALESCE(r.assessment_json::jsonb #>> '{evaluation,model}', '') <> 'Human'
                        ORDER BY r.version DESC LIMIT 1
                    ) a ON TRUE
                )
                SELECT count(*) AS reviewed,
                    count(*) FILTER (WHERE verdict IN ('YES', 'NO')) AS comparable,
                    count(*) FILTER (WHERE verdict IN ('YES', 'NO') AND
                        verdict <> CASE WHEN publication_status = 'PUBLISHED' THEN 'YES' ELSE 'NO' END) AS overrides
                FROM comparisons
                """, startTime, endTime);

        long total = count(evaluations, "total"), passed = count(evaluations, "passed");
        long comparable = count(reviews, "comparable"), overrides = count(reviews, "overrides");
        return new Metrics(first, last, "UTC", start, end, "OUTPUT_CONTENT_VERSION", total, passed,
                count(evaluations, "failed"), count(evaluations, "unavailable"), rate(passed, total),
                average(evaluations, "faithfulness"), count(evaluations, "faithfulness_count"),
                average(evaluations, "relevance"), count(evaluations, "relevance_count"),
                count(queue, "pending"), count(queue, "entered"), count(reviews, "reviewed"),
                comparable, overrides, rate(overrides, comparable));
    }

    private static long count(Map<String, Object> values, String key) { return ((Number) values.get(key)).longValue(); }
    private static Double average(Map<String, Object> values, String key) {
        return values.get(key) == null ? null : ((Number) values.get(key)).doubleValue();
    }
    private static Double rate(long numerator, long denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }
}
