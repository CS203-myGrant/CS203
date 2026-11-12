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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
import mygrant.impact.ImpactReviewController.Decision;
import mygrant.impact.ImpactReviewController.DecisionRequest;

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
    private RecommendationReportService reports;
    @Autowired
    void setReports(RecommendationReportService reports) { this.reports = reports; }
    private ImpactReviewPolicy reviewPolicy;
    @Autowired
    void setReviewPolicy(ImpactReviewPolicy reviewPolicy) { this.reviewPolicy = reviewPolicy; }

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
                       r.version, p.source_url, p.content, o.id AS output_id, r.output_type,
                       r.publication_status,
                       r.version, r.output_type, p.source_url, p.content, o.id AS output_id,
                       CAST(o.latest_evaluation_json::jsonb ->> 'citationsValid' AS BOOLEAN) AS citations_valid,
                       applicant.full_name AS applicant_name, applicant.email AS applicant_email
                FROM policy_recommendations r JOIN policy_documents p ON p.id = r.policy_id
                JOIN users applicant ON applicant.id = r.user_id
                LEFT JOIN stored_ai_outputs o ON o.review_id = r.id
                WHERE (r.publication_status = 'PENDING_REVIEW'
                    OR EXISTS (SELECT 1 FROM recommendation_reports rr
                               WHERE rr.assessment_id = r.id AND rr.status = 'OPEN'))
                """ + scope + """
                ORDER BY r.generated_at, r.id LIMIT 100
                """;
        List<PendingImpact> rows = reviewer.getRole() == UserRole.ADMIN
                ? jdbc.query(sql, (rs, row) -> pendingRow(rs))
                : jdbc.query(sql, (rs, row) -> pendingRow(rs), reviewer.getId());
        // CS203-97: applicant reports travel with the assessment they are about.
        var open = reports.openFor(rows.stream().map(PendingImpact::id).toList());
        return rows.stream().map(item -> item.withReports(open.getOrDefault(item.id(), List.of()))).toList();
    }

    /**
     * CS203-97: the reviewer checked the reported assessment and found it correct, so it stays
     * as published and the applicant's open reports are closed as dismissed.
     */
    @Transactional
    public ReportDismissal dismissReports(long id, String reviewerEmail, String note) {
        User reviewer = reviewer(reviewerEmail);
        if (lockInScope(id, reviewer).isEmpty() || !reports.hasOpen(id)) throw new ImpactNotFoundException();
        int closed = reports.close(id, reviewer.getId(), RecommendationReportService.Outcome.DISMISSED, note);
        return new ReportDismissal(id, closed, Instant.now());
    }

    /** The red badge on the reviewer's queue. */
    @Transactional(readOnly = true)
    public RecommendationReportService.OpenCount openReportCount(String reviewerEmail) {
        User reviewer = reviewer(reviewerEmail);
        return reports.openCount(reviewer.getId(), reviewer.getRole() == UserRole.ADMIN);
    }

    /** A reviewer may accept an unchanged draft or reject it without deleting its history. */
    @Transactional
    public ReviewResult decide(long id, String reviewerEmail, DecisionRequest request) {
        User reviewer = reviewer(reviewerEmail);
        List<Integer> versions = lockInScope(id, reviewer);
        if (versions.isEmpty()) throw new ImpactNotFoundException();
        int version = versions.getFirst();
        if (version != request.version()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This draft changed. Reload the review queue.");
        }
        var rows = jdbc.query("SELECT publication_status, output_type, assessment_json FROM policy_recommendations WHERE id = ?",
                (rs, row) -> new ReviewDraft(rs.getString(1), rs.getString(2), rs.getString(3)), id);
        ReviewDraft row = rows.getFirst();
        if (!"PENDING_REVIEW".equals(row.status()) || reports.hasOpen(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This item is no longer an unreviewed draft.");
        }
        ProfileImpactAssessment draft;
        try {
            draft = mapper.readValue(row.json(), ProfileImpactAssessment.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored pending impact is unreadable", exception);
        }
        if ("Policy extractor".equals(draft.provider())) {
            throw new IllegalArgumentException("Review the extracted country rules before publishing this policy.");
        }
        String note = request.note().trim();
        String status;
        if (request.decision() == Decision.VERIFY) {
            if (draft.status() != AssessmentStatus.COMPLETED || draft.urgency() == null) {
                throw new IllegalArgumentException("An unavailable result cannot be verified as an assessment.");
            }
            if ("ANSWER".equals(row.outputType())) {
                var evaluations = jdbc.query("SELECT latest_evaluation_json FROM stored_ai_outputs WHERE review_id = ?",
                        (rs, index) -> rs.getString(1), id);
                try {
                    if (evaluations.isEmpty() || !Boolean.TRUE.equals(
                            mapper.readValue(evaluations.getFirst(), JudgeEvaluation.class).citationsValid())) {
                        throw new IllegalArgumentException("This answer has unverified citations. Publish a corrected answer instead.");
                    }
                } catch (JsonProcessingException exception) {
                    throw new IllegalStateException("Stored evaluation is unreadable", exception);
                }
            }
            var verified = new ProfileImpactAssessment(draft.urgency(), draft.confidence(), draft.summary(),
                    draft.reasons(), draft.recommendedActions(), draft.missingInformation(), false,
                    draft.provider(), draft.model(), new JudgeEvaluation(JudgeEvaluation.Verdict.YES,
                    "Verified by a human reviewer", "Human"), draft.attempts());
            String json;
            try {
                json = mapper.writeValueAsString(verified);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Unable to store verified impact", exception);
            }
            jdbc.update("""
                    UPDATE policy_recommendations SET assessment_json = ?, publication_status = 'PUBLISHED',
                        reviewed_by_human = TRUE, reviewed_by_user_id = ?, reviewed_at = CURRENT_TIMESTAMP,
                        version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                    """, json, reviewer.getId(), id);
            if ("ANSWER".equals(row.outputType())) publication.publishHumanAnswer(id, draft.summary());
            else actionItems.syncForAssessment(id);
            status = "PUBLISHED";
        } else {
            jdbc.update("""
                    UPDATE policy_recommendations SET publication_status = 'REJECTED',
                        reviewed_by_human = TRUE, reviewed_by_user_id = ?, reviewed_at = CURRENT_TIMESTAMP,
                        version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                    """, reviewer.getId(), id);
            if ("ANSWER".equals(row.outputType())) publication.rejectHumanAnswer(id);
            status = "REJECTED";
        }
        jdbc.update("""
                INSERT INTO impact_review_decisions (assessment_id, reviewer_id, assessment_version, decision, note)
                VALUES (?, ?, ?, ?, ?)
                """, id, reviewer.getId(), version, request.decision().name(), note);
        return new ReviewResult(id, version + 1, true, status, Instant.now());
    }

    private record ReviewDraft(String status, String outputType, String json) {}

    /**
     * A reviewer answering an applicant's report must say why, and must actually change the
     * assessment; republishing it untouched would tell the applicant it was corrected when it
     * was not. Dismissing is the way to keep it as it is.
     */
    private void requireRealCorrection(long id, ProfileImpactAssessment corrected, String note) {
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("Explain the correction to the applicant before publishing.");
        }
        String current = jdbc.queryForObject("SELECT assessment_json FROM policy_recommendations WHERE id = ?",
                String.class, id);
        ProfileImpactAssessment before;
        try {
            before = mapper.readValue(current, ProfileImpactAssessment.class);
        } catch (JsonProcessingException exception) {
            return; // An unreadable stored assessment cannot be compared; any correction replaces it.
        }
        boolean unchanged = before.urgency() == corrected.urgency()
                && before.confidence() == corrected.confidence()
                && normalized(before.summary()).equals(normalized(corrected.summary()))
                && normalized(before.reasons()).equals(normalized(corrected.reasons()))
                && normalized(before.recommendedActions()).equals(normalized(corrected.recommendedActions()))
                && normalized(before.missingInformation()).equals(normalized(corrected.missingInformation()));
        if (unchanged) {
            throw new IllegalArgumentException("Nothing in the assessment was changed. Edit it to publish a "
                    + "correction, or dismiss the report if the assessment is already correct.");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static List<String> normalized(List<String> values) {
        return values == null ? List.of() : values.stream().map(ImpactReviewService::normalized)
                .filter(value -> !value.isEmpty()).toList();
    }

    /** Locks the assessment if the reviewer may act on it: admins on any, managers on linked applicants'. */
    private List<Integer> lockInScope(long id, User reviewer) {
        String scope = reviewer.getRole() == UserRole.ADMIN ? "" : """
                  AND EXISTS (
                      SELECT 1 FROM manager_user_memberships m
                      JOIN users owner ON owner.id = m.user_id
                      WHERE m.manager_id = ? AND m.user_id = r.user_id
                        AND owner.role = 'APPLICANT' AND owner.active = TRUE
                  )
                """;
        String sql = "SELECT r.version FROM policy_recommendations r WHERE r.id = ? " + scope + " FOR UPDATE";
        return reviewer.getRole() == UserRole.ADMIN
                ? jdbc.query(sql, (rs, row) -> rs.getInt(1), id)
                : jdbc.query(sql, (rs, row) -> rs.getInt(1), id, reviewer.getId());
    }

    @Transactional
    public ReviewResult publishCorrection(long id, String reviewerEmail, Correction correction) {
        User reviewer = reviewer(reviewerEmail);
        Long reviewerId = reviewer.getId();
        List<Integer> versions = lockInScope(id, reviewer);
        if (versions.isEmpty()) throw new ImpactNotFoundException();
        String currentStatus = jdbc.queryForObject(
                "SELECT publication_status FROM policy_recommendations WHERE id = ?", String.class, id);
        if ("REJECTED".equals(currentStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This draft was rejected. Create a new assessment rather than publishing the rejected output.");
        }
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
        boolean reported = reports.hasOpen(id);
        if (reported) requireRealCorrection(id, corrected, correction.reviewNote());
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
        // CS203-97: the published correction answers any applicant reports on this assessment.
        if (reported) reports.close(id, reviewerId, RecommendationReportService.Outcome.RESOLVED, correction.reviewNote());
        // CS203-153: a held background assessment was approved, so its processing record says so.
        jdbc.update("UPDATE impact_assessments SET review_status = 'APPROVED' "
                + "WHERE recommendation_id = ? AND review_status = 'PENDING_REVIEW'", id);
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
                    rs.getString("applicant_name"), rs.getString("applicant_email"),
                    rs.getLong("policy_id"), rs.getString("title"), assessment.summary(),
                    rs.getTimestamp("generated_at").toInstant(), rs.getString("source_url"),
                    reviewExcerpt(rs.getString("content")), assessment.provider(), rs.getString("output_type"),
                    rs.getObject("citations_valid", Boolean.class), rs.getObject("output_id", java.util.UUID.class),
                    rs.getInt("version"), assessment.urgency(), assessment.confidence(), assessment.reasons(),
                    assessment.recommendedActions(), assessment.missingInformation(), holdReasons(rs, assessment),
                    List.of());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored pending impact is unreadable", exception);
        }
    }

    /** Why an AI assessment draft was held; empty for reported, extraction and answer items. */
    private List<String> holdReasons(ResultSet rs, ProfileImpactAssessment assessment) throws SQLException {
        if (reviewPolicy == null || !"PENDING_REVIEW".equals(rs.getString("publication_status"))
                || !"ASSESSMENT".equals(rs.getString("output_type")) || "Policy extractor".equals(assessment.provider())
                || assessment.status() != AssessmentStatus.COMPLETED) {
            return List.of();
        }
        return reviewPolicy.holdReasons(assessment).stream().map(reason -> reason == ImpactReviewPolicy.HoldReason.LOW_CONFIDENCE
                ? reason.label() + " (" + assessment.confidence() + "%)" : reason.label()).toList();
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

    public record PendingImpact(long id, long userId,
            @io.swagger.v3.oas.annotations.media.Schema(description = "The applicant's name, for the reviewer")
            String applicantName,
            @io.swagger.v3.oas.annotations.media.Schema(description = "The applicant's email, for the reviewer")
            String applicantEmail,
            long policyId,
            String policyTitle, String draftSummary, Instant createdAt,
            String sourceUrl, String sourceExcerpt, String reviewType, String outputType,
            Boolean citationsValid, java.util.UUID outputId,
            int version, ImpactUrgency draftUrgency, int draftConfidence, List<String> draftAnalysis,
            List<String> draftActionItems, List<String> draftMissingInformation,
            @io.swagger.v3.oas.annotations.media.Schema(description =
                    "Why an AI assessment draft was held for review, e.g. a failed independent check or low "
                    + "confidence; empty for reported, extraction and answer items")
            List<String> holdReasons,
            @io.swagger.v3.oas.annotations.media.Schema(description =
                    "Open applicant reports on this assessment; empty when it is queued for another reason")
            List<RecommendationReportService.Report> reports) {
        PendingImpact withReports(List<RecommendationReportService.Report> open) {
            return new PendingImpact(id, userId, applicantName, applicantEmail, policyId, policyTitle, draftSummary,
                    createdAt, sourceUrl, sourceExcerpt, reviewType, outputType, citationsValid, outputId, version,
                    draftUrgency, draftConfidence, draftAnalysis, draftActionItems, draftMissingInformation,
                    holdReasons, List.copyOf(open));
        }
    }
    public record ReportDismissal(long id, int dismissedReports, Instant dismissedAt) {}
    public record ReviewResult(long id, int version, boolean reviewedByHuman,
            String publicationStatus, Instant reviewedAt) {}
}
