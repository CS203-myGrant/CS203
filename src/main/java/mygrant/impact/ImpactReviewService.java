package mygrant.impact;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import mygrant.actions.ActionItemService;
import mygrant.user.UserRepository;
import mygrant.user.User;
import mygrant.user.UserRole;
import mygrant.policies.CountryIdentifiers;
import mygrant.policies.PolicyIndexedEvent;
import mygrant.impact.ImpactReviewController.Correction;
import mygrant.impact.ImpactReviewController.CountryRuleCorrection;

/** Manager correction and publication, with revisions retained by the database trigger. */
@Service
public class ImpactReviewService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final UserRepository users;
    private final ActionItemService actionItems;
    private final ApplicationEventPublisher eventPublisher;
    private OutputPublicationService publication;
    @Autowired
    void setPublication(OutputPublicationService publication) { this.publication = publication; }

    @Autowired
    public ImpactReviewService(JdbcTemplate jdbc, ObjectMapper mapper, UserRepository users,
            ActionItemService actionItems, ApplicationEventPublisher eventPublisher) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.users = users;
        this.actionItems = actionItems;
        this.eventPublisher = eventPublisher;
    }

    public ImpactReviewService(JdbcTemplate jdbc, ObjectMapper mapper, UserRepository users,
            ActionItemService actionItems) {
        this(jdbc, mapper, users, actionItems, event -> {});
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
                SELECT r.id, r.user_id, r.policy_id, p.title, r.assessment_json, r.generated_at,
                       p.source_url, p.content, o.id AS output_id
                FROM policy_recommendations r JOIN policy_documents p ON p.id = r.policy_id
                LEFT JOIN stored_ai_outputs o ON o.review_id = r.id
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
        String provider = jdbc.queryForObject("SELECT assessment_json::jsonb ->> 'provider' FROM policy_recommendations WHERE id = ?",
                String.class, id);
        if ("Policy extractor".equals(provider)) {
            return approveExtractionReview(id, reviewerId, versions.getFirst(), correction);
        }
        String severity = correction.severity().trim().toUpperCase(java.util.Locale.ROOT);
        final ImpactUrgency urgency;
        try {
            urgency = ImpactUrgency.valueOf(severity);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unknown impact severity", invalid);
        }
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
                    requires_review = TRUE, review_status = 'APPROVED',
                    reviewed_by_user_id = ?, reviewed_at = CURRENT_TIMESTAMP,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, json, severity, reviewerId, id);
        String outputType = jdbc.queryForObject("SELECT output_type FROM policy_recommendations WHERE id = ?", String.class, id);
        if ("ANSWER".equals(outputType)) {
            publication.publishHumanAnswer(id, correction.summary().trim());
            return new ReviewResult(id, versions.getFirst() + 1, true, "PUBLISHED", Instant.now());
        }
        // The correction's actions replace the old ones; the owner's progress on kept items stays.
        actionItems.syncForAssessment(id);
        return new ReviewResult(id, versions.getFirst() + 1, true, "PUBLISHED", Instant.now());
    }

    private ReviewResult approveExtractionReview(long id, long reviewerId, int version, Correction correction) {
        var rows = jdbc.query("""
                SELECT r.policy_id, p.external_id, p.title, p.visa_type, p.effective_date, p.publication_date,
                       p.content, p.source_url, p.special_document_holder_rule
                FROM policy_recommendations r JOIN policy_documents p ON p.id = r.policy_id WHERE r.id = ?
                """, (rs, row) -> new Object[] { rs.getLong("policy_id"), rs.getString("external_id"),
                rs.getString("title"), rs.getString("visa_type"), rs.getDate("effective_date"),
                rs.getDate("publication_date"), rs.getString("content"), rs.getString("source_url"),
                rs.getBoolean("special_document_holder_rule") }, id);
        if (rows.isEmpty()) throw new ImpactNotFoundException();
        Object[] row = rows.getFirst();
        long policyId = (long) row[0];
        var rules = correction.countryRestrictions() == null ? List.<CountryRuleCorrection>of()
                : correction.countryRestrictions();
        var codes = rules.stream().map(rule -> CountryIdentifiers.normalize(rule.countryCode())).distinct().toList();
        String exemptionWording = reviewExemption((String) row[6]);
        jdbc.update("DELETE FROM policy_affected_countries WHERE policy_id = ?", policyId);
        jdbc.update("DELETE FROM policy_country_restrictions WHERE policy_id = ?", policyId);
        jdbc.update("DELETE FROM policy_source_references WHERE policy_id = ?", policyId);
        jdbc.update("DELETE FROM policy_restriction_categories WHERE policy_id = ?", policyId);
        jdbc.update("INSERT INTO policy_restriction_categories(policy_id, category) VALUES (?, 'ENTRY_RESTRICTION')", policyId);
        for (String code : codes) {
            jdbc.update("INSERT INTO policy_affected_countries(policy_id, country_code) VALUES (?, ?)", policyId, code);
        }
        for (int i = 0; i < rules.size(); i++) {
            String code = CountryIdentifiers.normalize(rules.get(i).countryCode());
            jdbc.update("INSERT INTO policy_country_restrictions(policy_id, rule_order, country_code, restriction_scope, source_reference, exemption_wording) VALUES (?, ?, ?, ?, ?, ?)",
                    policyId, i, code, rules.get(i).scope().name(), row[7] + "#human-reviewed", exemptionWording);
        }
        jdbc.update("INSERT INTO policy_source_references(policy_id, reference_order, source_reference) VALUES (?, 0, ?)",
                policyId, row[7] + "#human-reviewed");
        jdbc.update("UPDATE policy_documents SET extraction_review_required = FALSE WHERE id = ?", policyId);
        jdbc.update("DELETE FROM policy_recommendations WHERE id = ?", id);
        var effective = (java.sql.Date) row[4];
        var publication = (java.sql.Date) row[5];
        var countries = java.util.Set.copyOf(codes);
        eventPublisher.publishEvent(new PolicyIndexedEvent(policyId, (String) row[2],
                java.util.Arrays.stream(((String) row[3]).split(",")).map(String::trim).toList(),
                effective == null ? null : effective.toLocalDate(), (String) row[6],
                publication == null ? null : publication.toLocalDate(), countries, true, false,
                (boolean) row[8]));
        return new ReviewResult(id, version + 1, true, "EXTRACTION_APPROVED", Instant.now());
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
                    rs.getTimestamp("generated_at").toInstant(), rs.getString("source_url"),
                    reviewExcerpt(rs.getString("content")), assessment.provider(), rs.getObject("output_id", java.util.UUID.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored pending impact is unreadable", exception);
        }
    }

    private static String reviewExcerpt(String content) {
        if (content == null) return "";
        String lower = content.toLowerCase(java.util.Locale.ROOT);
        int start = lower.indexOf("section 4");
        if (start < 0) start = lower.indexOf("sec. 4");
        if (start < 0) start = lower.indexOf("exceptions");
        if (start < 0) start = 0;
        int end = Math.min(content.length(), start + 3000);
        int exceptions = lower.indexOf("section 6", start + 1);
        if (exceptions < 0) exceptions = lower.indexOf("exceptions", end);
        if (exceptions >= 0 && exceptions > end) {
            return content.substring(start, end) + "\n\n[Exceptions section]\n"
                    + content.substring(exceptions, Math.min(content.length(), exceptions + 2000));
        }
        return content.substring(start, Math.min(content.length(), start + 4000));
    }

    private static String reviewExemption(String content) {
        if (content == null) return "";
        String lower = content.toLowerCase(java.util.Locale.ROOT);
        int start = lower.indexOf("section 6");
        if (start < 0) start = lower.indexOf("sec. 6");
        if (start < 0) return "";
        int end = lower.indexOf("section 7", start + 1);
        if (end < 0) end = lower.indexOf("sec. 7", start + 1);
        if (end < 0) end = Math.min(content.length(), start + 8000);
        return content.substring(start, end).strip();
    }

    public record PendingImpact(long id, long userId, long policyId,
            String policyTitle, String draftSummary, Instant createdAt,
            String sourceUrl, String sourceExcerpt, String reviewType, java.util.UUID outputId) {}
    public record ReviewResult(long id, int version, boolean reviewedByHuman,
            String publicationStatus, Instant reviewedAt) {}
}
