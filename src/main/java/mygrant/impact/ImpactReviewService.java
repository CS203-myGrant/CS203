package mygrant.impact;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import mygrant.actions.ActionItemService;
import mygrant.user.UserRepository;
import mygrant.user.User;
import mygrant.user.UserRole;
import mygrant.impact.ImpactReviewController.Correction;

/** Manager correction and publication, with revisions retained by the database trigger. */
@Service
public class ImpactReviewService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final UserRepository users;
    private final ActionItemService actionItems;

    public ImpactReviewService(JdbcTemplate jdbc, ObjectMapper mapper, UserRepository users,
            ActionItemService actionItems) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.users = users;
        this.actionItems = actionItems;
    }

    @Transactional(readOnly = true)
    public List<PendingImpact> pending(String reviewerEmail) {
        User reviewer = reviewer(reviewerEmail);
        String scope = "";
        if (reviewer.getRole() != UserRole.ADMIN) {
            scope = """
                  AND EXISTS (
                      SELECT 1 FROM manager_user_memberships m
                      JOIN users owner ON owner.id = m.user_id
                      WHERE m.manager_id = ? AND m.user_id = r.user_id
                        AND owner.role = 'APPLICANT' AND owner.active = TRUE
                  )
                """;
        }
        String sql = """
                SELECT r.id, r.user_id, r.policy_id, p.title, r.assessment_json, r.generated_at
                FROM policy_recommendations r JOIN policy_documents p ON p.id = r.policy_id
                WHERE r.publication_status = 'PENDING_REVIEW'
                """ + scope + """
                ORDER BY r.generated_at, r.id LIMIT 100
                """;
        return reviewer.getRole() == UserRole.ADMIN
                ? jdbc.query(sql, (rs, row) -> pendingRow(rs))
                : jdbc.query(sql, (rs, row) -> pendingRow(rs), reviewer.getId());
    }

    @Transactional
    public ReviewResult publishCorrection(long id, String reviewerEmail, Correction correction) {
        User reviewer = reviewer(reviewerEmail);
        Long reviewerId = reviewer.getId();
        String scope = "";
        if (reviewer.getRole() != UserRole.ADMIN) {
            scope = """
                  AND EXISTS (
                      SELECT 1 FROM manager_user_memberships m
                      JOIN users owner ON owner.id = m.user_id
                      WHERE m.manager_id = ? AND m.user_id = r.user_id
                        AND owner.role = 'APPLICANT' AND owner.active = TRUE
                  )
                """;
        }
        String sql = "SELECT r.version FROM policy_recommendations r WHERE r.id = ? "
                + scope + " FOR UPDATE";
        List<Integer> versions = reviewer.getRole() == UserRole.ADMIN
                ? jdbc.query(sql, (rs, row) -> rs.getInt(1), id)
                : jdbc.query(sql, (rs, row) -> rs.getInt(1), id, reviewerId);
        if (versions.isEmpty()) throw new ImpactNotFoundException();
        String severity = correction.severity().toUpperCase(java.util.Locale.ROOT);
        ImpactUrgency urgency = switch (severity) {
            case "CRITICAL", "HIGH" -> ImpactUrgency.HIGH;
            case "MEDIUM" -> ImpactUrgency.MEDIUM;
            case "LOW" -> ImpactUrgency.LOW;
            case "INFO" -> ImpactUrgency.NOT_APPLICABLE;
            default -> throw new IllegalArgumentException("Unknown impact severity");
        };
        var corrected = new ProfileImpactAssessment(urgency, correction.confidence(),
                correction.summary().trim(), List.copyOf(correction.analysis()),
                List.copyOf(correction.actionItems()), List.copyOf(correction.missingInformation()),
                false, "Human review", "Manager correction",
                new JudgeEvaluation(JudgeEvaluation.Verdict.YES,
                        "Approved by a human reviewer", "Human"), 1);
        String json;
        try {
            json = mapper.writeValueAsString(corrected);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to store corrected impact", exception);
        }
        jdbc.update("""
                UPDATE policy_recommendations SET assessment_json = ?, severity = ?,
                    publication_status = 'PUBLISHED', reviewed_by_human = TRUE,
                    reviewed_by_user_id = ?, reviewed_at = CURRENT_TIMESTAMP,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, json, severity, reviewerId, id);
        // The correction's actions replace the old ones; the owner's progress on kept items stays.
        actionItems.syncForAssessment(id);
        return new ReviewResult(id, versions.getFirst() + 1, true, "PUBLISHED", Instant.now());
    }

    private User reviewer(String email) {
        User reviewer = users.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Reviewer no longer exists"));
        if (reviewer.getRole() != UserRole.MANAGER && reviewer.getRole() != UserRole.ADMIN) {
            throw new AccessDeniedException("Reviewer role required");
        }
        return reviewer;
    }

    private PendingImpact pendingRow(ResultSet rs) throws SQLException {
        try {
            var assessment = mapper.readValue(rs.getString("assessment_json"), ProfileImpactAssessment.class);
            return new PendingImpact(rs.getLong("id"), rs.getLong("user_id"),
                    rs.getLong("policy_id"), rs.getString("title"), assessment.summary(),
                    rs.getTimestamp("generated_at").toInstant());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored pending impact is unreadable", exception);
        }
    }

    public record PendingImpact(long id, long userId, long policyId,
            String policyTitle, String draftSummary, Instant createdAt) {}
    public record ReviewResult(long id, int version, boolean reviewedByHuman,
            String publicationStatus, Instant reviewedAt) {}
}
