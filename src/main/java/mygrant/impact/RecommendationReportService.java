package mygrant.impact;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mygrant.user.UserRepository;

/**
 * CS203-97 / CS203-6: applicants report a problem with one of their published assessments.
 * Each report is categorised, tied to the assessment and the version the applicant saw, and
 * keeps the assessment in the reviewer queue until a reviewer corrects or dismisses it. The
 * assessment itself stays visible meanwhile, so a report can never hide an urgent warning.
 */
@Service
public class RecommendationReportService {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final UserRepository users;

    public RecommendationReportService(JdbcTemplate jdbc, NamedParameterJdbcTemplate named, UserRepository users) {
        this.jdbc = jdbc;
        this.named = named;
        this.users = users;
    }

    @Transactional
    public Report report(String email, long assessmentId, ReportCategory category, String details, int version) {
        long userId = userId(email);
        List<Integer> current = jdbc.query("SELECT r.version FROM policy_recommendations r WHERE r.id = ? "
                + "AND r.user_id = ? AND " + ImpactVisibility.VISIBLE + " FOR UPDATE",
                (rs, row) -> rs.getInt(1), assessmentId, userId);
        if (current.isEmpty()) throw new ImpactNotFoundException();
        if (current.getFirst() != version) {
            throw new RecommendationReportConflictException(
                    "This assessment has changed since you opened it. Reload it and check the new version before reporting.");
        }
        // The row lock above serialises reports on this assessment, so this check cannot race.
        Long open = jdbc.queryForObject("SELECT count(*) FROM recommendation_reports "
                + "WHERE assessment_id = ? AND user_id = ? AND status = 'OPEN'", Long.class, assessmentId, userId);
        if (open != null && open > 0) {
            throw new RecommendationReportConflictException(
                    "You have already reported this assessment. A reviewer will check it.");
        }
        try {
            return jdbc.queryForObject("""
                    INSERT INTO recommendation_reports (assessment_id, user_id, assessment_version, category, details)
                    VALUES (?, ?, ?, ?, ?)
                    RETURNING id, assessment_id, assessment_version, category, details, status, created_at, resolved_at
                    """, (rs, row) -> report(rs), assessmentId, userId, version, category.name(), details.trim());
        } catch (DuplicateKeyException duplicate) {
            throw new RecommendationReportConflictException(
                    "You have already reported this assessment. A reviewer will check it.");
        }
    }

    /** The signed-in applicant's reports on one of their visible assessments, newest first. */
    @Transactional(readOnly = true)
    public List<Report> mine(String email, long assessmentId) {
        long userId = userId(email);
        Integer visible = jdbc.query("SELECT 1 FROM policy_recommendations r WHERE r.id = ? AND r.user_id = ? AND "
                + ImpactVisibility.VISIBLE, (rs, row) -> 1, assessmentId, userId).stream().findFirst().orElse(null);
        if (visible == null) throw new ImpactNotFoundException();
        return jdbc.query("""
                SELECT id, assessment_id, assessment_version, category, details, status, created_at, resolved_at
                FROM recommendation_reports WHERE assessment_id = ? AND user_id = ?
                ORDER BY created_at DESC, id DESC
                """, (rs, row) -> report(rs), assessmentId, userId);
    }

    /** Open reports for the given assessments, oldest first, grouped by assessment. */
    @Transactional(readOnly = true)
    public Map<Long, List<Report>> openFor(Collection<Long> assessmentIds) {
        if (assessmentIds.isEmpty()) return Map.of();
        return named.query("""
                SELECT id, assessment_id, assessment_version, category, details, status, created_at, resolved_at
                FROM recommendation_reports WHERE status = 'OPEN' AND assessment_id IN (:ids)
                ORDER BY created_at, id
                """, new MapSqlParameterSource("ids", assessmentIds), (rs, row) -> report(rs))
                .stream().collect(Collectors.groupingBy(Report::assessmentId));
    }

    /** Closes every open report on the assessment once a reviewer has acted on it. */
    @Transactional
    public int close(long assessmentId, long reviewerId, Outcome outcome) {
        return jdbc.update("""
                UPDATE recommendation_reports SET status = ?, resolved_at = CURRENT_TIMESTAMP, resolved_by_user_id = ?
                WHERE assessment_id = ? AND status = 'OPEN'
                """, outcome.name(), reviewerId, assessmentId);
    }

    private static Report report(java.sql.ResultSet rs) throws java.sql.SQLException {
        var resolved = rs.getTimestamp("resolved_at");
        return new Report(rs.getLong("id"), rs.getLong("assessment_id"), rs.getInt("assessment_version"),
                ReportCategory.valueOf(rs.getString("category")), rs.getString("details"),
                rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                resolved == null ? null : resolved.toInstant());
    }

    private long userId(String email) {
        return users.findByEmail(email).orElseThrow(() -> new IllegalStateException(
                "Authenticated user no longer exists")).getId();
    }

    public enum Outcome { RESOLVED, DISMISSED }

    @io.swagger.v3.oas.annotations.media.Schema(description = "An applicant's report on one assessment")
    public record Report(long id, long assessmentId,
            @io.swagger.v3.oas.annotations.media.Schema(description = "The assessment version the applicant reported")
            int assessmentVersion, ReportCategory category, String details,
            @io.swagger.v3.oas.annotations.media.Schema(allowableValues = {"OPEN", "RESOLVED", "DISMISSED"})
            String status, Instant createdAt, Instant resolvedAt) {}
}
