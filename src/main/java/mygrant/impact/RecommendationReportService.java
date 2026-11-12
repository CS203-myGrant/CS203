package mygrant.impact;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        // Several separate problems may be reported, but not without limit. The row lock above
        // serialises reports on this assessment, so this check cannot race.
        Long open = jdbc.queryForObject("SELECT count(*) FROM recommendation_reports "
                + "WHERE assessment_id = ? AND user_id = ? AND status = 'OPEN'", Long.class, assessmentId, userId);
        if (open != null && open >= MAX_OPEN_REPORTS) {
            throw new RecommendationReportConflictException("You already have " + MAX_OPEN_REPORTS
                    + " reports waiting for review on this assessment. Please wait for a reviewer before sending more.");
        }
        return jdbc.queryForObject("""
                INSERT INTO recommendation_reports (assessment_id, user_id, assessment_version, category, details)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id, assessment_id, assessment_version, category, details, status, created_at, resolved_at, resolution_note, archived_at
                """, (rs, row) -> report(rs), assessmentId, userId, version, category.name(), details.trim());
    }

    /** How many of one applicant's reports may wait for review on a single assessment. */
    static final int MAX_OPEN_REPORTS = 5;

    /** The signed-in applicant's reports on one of their visible assessments, newest first. */
    @Transactional(readOnly = true)
    public List<Report> mine(String email, long assessmentId) {
        long userId = userId(email);
        Integer visible = jdbc.query("SELECT 1 FROM policy_recommendations r WHERE r.id = ? AND r.user_id = ? AND "
                + ImpactVisibility.VISIBLE, (rs, row) -> 1, assessmentId, userId).stream().findFirst().orElse(null);
        if (visible == null) throw new ImpactNotFoundException();
        return jdbc.query("""
                SELECT id, assessment_id, assessment_version, category, details, status, created_at, resolved_at, resolution_note, archived_at
                FROM recommendation_reports WHERE assessment_id = ? AND user_id = ?
                ORDER BY created_at DESC, id DESC
                """, (rs, row) -> report(rs), assessmentId, userId);
    }

    /** Open reports for the given assessments, oldest first, grouped by assessment. */
    @Transactional(readOnly = true)
    public Map<Long, List<Report>> openFor(Collection<Long> assessmentIds) {
        if (assessmentIds.isEmpty()) return Map.of();
        return named.query("""
                SELECT id, assessment_id, assessment_version, category, details, status, created_at, resolved_at, resolution_note, archived_at
                FROM recommendation_reports WHERE status = 'OPEN' AND assessment_id IN (:ids)
                ORDER BY created_at, id
                """, new MapSqlParameterSource("ids", assessmentIds), (rs, row) -> report(rs))
                .stream().collect(Collectors.groupingBy(Report::assessmentId));
    }

    /** True while the applicant has at least one report on the assessment waiting for review. */
    @Transactional(readOnly = true)
    public boolean hasOpen(long assessmentId) {
        Long open = jdbc.queryForObject("SELECT count(*) FROM recommendation_reports WHERE assessment_id = ? "
                + "AND status = 'OPEN'", Long.class, assessmentId);
        return open != null && open > 0;
    }

    /**
     * Closes every open report on the assessment once a reviewer has acted on it. The note is
     * the reviewer's explanation and is shown to the applicant with the outcome.
     */
    @Transactional
    public int close(long assessmentId, long reviewerId, Outcome outcome, String note) {
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("Explain your decision to the applicant before closing their report.");
        }
        return jdbc.update("""
                UPDATE recommendation_reports SET status = ?, resolved_at = CURRENT_TIMESTAMP, resolved_by_user_id = ?,
                    resolution_note = ?
                WHERE assessment_id = ? AND status = 'OPEN'
                """, outcome.name(), reviewerId, note.trim(), assessmentId);
    }

    /**
     * The badge on the reviewer's queue: open reports and the assessments they are on. Admins
     * count every applicant's; managers count only active applicants linked to them.
     */
    @Transactional(readOnly = true)
    public OpenCount openCount(long reviewerId, boolean admin) {
        String scope = admin ? "" : """
                  AND EXISTS (
                      SELECT 1 FROM manager_user_memberships m
                      JOIN users owner ON owner.id = m.user_id
                      WHERE m.manager_id = ? AND m.user_id = rr.user_id
                        AND owner.role = 'APPLICANT' AND owner.active = TRUE
                  )
                """;
        String sql = "SELECT count(*) AS reports, count(DISTINCT rr.assessment_id) AS assessments "
                + "FROM recommendation_reports rr WHERE rr.status = 'OPEN' " + scope;
        org.springframework.jdbc.core.RowMapper<OpenCount> mapper =
                (rs, row) -> new OpenCount(rs.getLong("reports"), rs.getLong("assessments"));
        return admin ? jdbc.queryForObject(sql, mapper) : jdbc.queryForObject(sql, mapper, reviewerId);
    }

    private static Report report(java.sql.ResultSet rs) throws java.sql.SQLException {
        var resolved = rs.getTimestamp("resolved_at");
        var archived = rs.getTimestamp("archived_at");
        return new Report(rs.getLong("id"), rs.getLong("assessment_id"), rs.getInt("assessment_version"),
                ReportCategory.valueOf(rs.getString("category")), rs.getString("details"),
                rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                resolved == null ? null : resolved.toInstant(), rs.getString("resolution_note"),
                archived == null ? null : archived.toInstant());
    }

    /**
     * Archives or restores one of the applicant's answered reports. Archiving also counts as
     * having seen the outcome. A report still waiting for review cannot be archived.
     */
    @Transactional
    public Report archive(String email, long assessmentId, long reportId, boolean archived) {
        long userId = userId(email);
        List<String> status = jdbc.query("""
                SELECT rr.status FROM recommendation_reports rr JOIN policy_recommendations r ON r.id = rr.assessment_id
                WHERE rr.id = ? AND rr.assessment_id = ? AND rr.user_id = ? AND """ + ImpactVisibility.VISIBLE
                + " FOR UPDATE OF rr", (rs, row) -> rs.getString(1), reportId, assessmentId, userId);
        if (status.isEmpty()) throw new ImpactNotFoundException();
        if ("OPEN".equals(status.getFirst())) {
            throw new RecommendationReportConflictException(
                    "This report is still waiting for review. You can archive it once a reviewer has answered.");
        }
        return jdbc.queryForObject("""
                UPDATE recommendation_reports SET
                    archived_at = CASE WHEN ? THEN coalesce(archived_at, CURRENT_TIMESTAMP) ELSE NULL END,
                    seen_by_applicant_at = CASE WHEN ? THEN coalesce(seen_by_applicant_at, CURRENT_TIMESTAMP)
                        ELSE seen_by_applicant_at END
                WHERE id = ?
                RETURNING id, assessment_id, assessment_version, category, details, status, created_at, resolved_at,
                    resolution_note, archived_at
                """, (rs, row) -> report(rs), archived, archived, reportId);
    }

    @io.swagger.v3.oas.annotations.media.Schema(description = "Open applicant reports waiting in the reviewer's queue")
    public record OpenCount(long openReports, long reportedAssessments) {}

    /**
     * The applicant's header bell: reviewer outcomes on their reports, newest first, with how
     * many they have not opened yet. Only assessments they can still see are included.
     */
    @Transactional(readOnly = true)
    public Updates updates(String email) {
        long userId = userId(email);
        String owned = "FROM recommendation_reports rr JOIN policy_recommendations r ON r.id = rr.assessment_id "
                + "JOIN policy_documents p ON p.id = r.policy_id "
                + "WHERE rr.user_id = ? AND rr.status <> 'OPEN' AND rr.archived_at IS NULL AND "
                + ImpactVisibility.VISIBLE + " ";
        Long unseen = jdbc.queryForObject("SELECT count(*) " + owned + "AND rr.seen_by_applicant_at IS NULL",
                Long.class, userId);
        List<Update> items = jdbc.query("""
                SELECT rr.id, rr.assessment_id, p.title, rr.category, rr.status, rr.resolved_at,
                       rr.resolution_note, rr.seen_by_applicant_at IS NOT NULL AS seen
                """ + owned + "ORDER BY rr.resolved_at DESC, rr.id DESC LIMIT 20",
                (rs, row) -> new Update(rs.getLong("id"), rs.getLong("assessment_id"), rs.getString("title"),
                        ReportCategory.valueOf(rs.getString("category")), rs.getString("status"),
                        rs.getTimestamp("resolved_at").toInstant(), rs.getString("resolution_note"),
                        rs.getBoolean("seen")), userId);
        return new Updates(unseen == null ? 0 : unseen, items);
    }

    /** Marks the applicant's outcomes as seen: all of them, or only those on one assessment. */
    @Transactional
    public int markSeen(String email, Long assessmentId) {
        long userId = userId(email);
        String sql = "UPDATE recommendation_reports SET seen_by_applicant_at = CURRENT_TIMESTAMP "
                + "WHERE user_id = ? AND status <> 'OPEN' AND seen_by_applicant_at IS NULL";
        return assessmentId == null ? jdbc.update(sql, userId)
                : jdbc.update(sql + " AND assessment_id = ?", userId, assessmentId);
    }

    @io.swagger.v3.oas.annotations.media.Schema(description = "Reviewer outcomes on the applicant's reports")
    public record Updates(long unseen, List<Update> items) {}

    @io.swagger.v3.oas.annotations.media.Schema(description = "A reviewer's outcome on one of the applicant's reports")
    public record Update(long reportId, long assessmentId, String policyTitle, ReportCategory category,
            @io.swagger.v3.oas.annotations.media.Schema(allowableValues = {"RESOLVED", "DISMISSED"}) String status,
            Instant resolvedAt, String resolutionNote, boolean seen) {}

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
            String status, Instant createdAt, Instant resolvedAt,
            @io.swagger.v3.oas.annotations.media.Schema(description = "The reviewer's explanation once the report is closed")
            String resolutionNote,
            @io.swagger.v3.oas.annotations.media.Schema(description = "When the applicant archived it; null if not archived")
            Instant archivedAt) {}
}
