package mygrant.impact;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.ArrayList;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import mygrant.actions.ActionItemService;
import mygrant.actions.ActionItemView;
import mygrant.user.UserRepository;

/** Published, owner-scoped impact assessments built on existing policy recommendations. */
@Service
public class ImpactFeedService {
    private static final String VISIBLE = """
            FROM policy_recommendations r JOIN policy_documents p ON p.id = r.policy_id
            WHERE r.user_id = :userId AND """ + ImpactVisibility.VISIBLE + "\n";
    private static final String CONSOLIDATED = """
            FROM (
                SELECT DISTINCT ON (r.policy_id) r.*
                FROM policy_recommendations r
                WHERE r.user_id = :userId AND r.archived_at IS NULL AND """ + ImpactVisibility.VISIBLE + """
                ORDER BY r.policy_id, r.generated_at DESC, r.id DESC
            ) r JOIN policy_documents p ON p.id = r.policy_id
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final UserRepository users;
    private final ActionItemService actionItems;

    public ImpactFeedService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, UserRepository users,
            ActionItemService actionItems) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.users = users;
        this.actionItems = actionItems;
    }

    @Transactional(readOnly = true)
    public Page<ImpactView> list(String email, Pageable pageable, String severity, boolean archived) {
        if (pageable.getPageSize() > 100 || pageable.getPageSize() < 1) {
            throw new IllegalArgumentException("Page size must be between 1 and 100");
        }
        String order = orderBy(pageable);
        String where = VISIBLE + (archived ? " AND r.archived_at IS NOT NULL" : " AND r.archived_at IS NULL");
        long userId = userId(email);
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId)
                .addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        if (severity != null && !severity.isBlank()) {
            params.addValue("severity", parseSeverity(severity));
            where += " AND r.severity = :severity";
        }
        Long total = jdbc.queryForObject("SELECT count(*) " + where, params, Long.class);
        List<ImpactView> rows = withActionItems(userId, jdbc.query(
                "SELECT r.*, p.title AS policy_title, p.agency AS policy_agency, "
                + "p.source_url AS policy_source_url, " + citationSql() + " " + where + " ORDER BY " + order
                + " LIMIT :limit OFFSET :offset", params, (rs, row) -> map(rs)));
        return new PageImpl<>(rows, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                pageable.getSort()), total == null ? 0 : total);
    }

    /** One current assessment per policy, projected as a combined consumer-facing view. */
    @Transactional(readOnly = true)
    public ConsolidatedAssessment consolidated(String email) {
        long userId = userId(email);
        var params = new MapSqlParameterSource("userId", userId);
        List<ImpactView> breakdown = jdbc.query("SELECT r.*, p.title AS policy_title, "
                        + "p.agency AS policy_agency, p.source_url AS policy_source_url, " + citationSql()
                        + " " + CONSOLIDATED + " ORDER BY r.generated_at DESC, r.id DESC", params,
                (rs, row) -> map(rs));
        breakdown = withActionItems(userId, breakdown);
        return aggregate(breakdown);
    }

    static ConsolidatedAssessment aggregate(List<ImpactView> breakdown) {
        ImpactUrgency netStatus = breakdown.stream().map(ImpactView::urgency)
                .max(java.util.Comparator.comparingInt(ImpactFeedService::urgencyRank))
                .orElse(ImpactUrgency.NOT_APPLICABLE);
        LinkedHashMap<String, Citation> citations = new LinkedHashMap<>();
        List<String> uncertainty = new ArrayList<>();
        for (ImpactView impact : breakdown) {
            impact.citations().stream().filter(citation -> citation.url() != null && !citation.url().isBlank())
                    .forEach(citation -> citations.putIfAbsent(citation.url(), citation));
            impact.missingInformation().forEach(field -> uncertainty.add(impact.policyTitle() + ": " + field));
            if (impact.confidence() < 60) {
                uncertainty.add(impact.policyTitle() + ": assessment confidence is low ("
                        + impact.confidence() + "%).");
            }
            if (impact.requiresHumanReview()) {
                uncertainty.add(impact.policyTitle() + ": human review is recommended for this assessment.");
            }
        }
        return new ConsolidatedAssessment(netStatus, List.copyOf(citations.values()),
                uncertainty.stream().distinct().toList(), breakdown);
    }

    private static int urgencyRank(ImpactUrgency urgency) {
        return switch (urgency) {
            case HIGH -> 4;
            case MEDIUM -> 3;
            case LOW -> 2;
            case NOT_APPLICABLE -> 1;
        };
    }

    @Transactional(readOnly = true)
    public ImpactView get(String email, long id) {
        long userId = userId(email);
        return withActionItems(userId, List.of(visible(userId, id))).getFirst();
    }

    /** Locate the applicant's newest published assessment for the policy detail report form. */
    @Transactional(readOnly = true)
    public ImpactView latestForPolicy(String email, long policyId) {
        long userId = userId(email);
        var params = new MapSqlParameterSource("userId", userId).addValue("policyId", policyId);
        ImpactView latest = jdbc.query("SELECT r.*, p.title AS policy_title, p.agency AS policy_agency, "
                + "p.source_url AS policy_source_url, " + citationSql() + " " + VISIBLE
                + " AND r.policy_id = :policyId ORDER BY r.generated_at DESC, r.id DESC LIMIT 1",
                params, (rs, row) -> map(rs)).stream().findFirst().orElseThrow(ImpactNotFoundException::new);
        return withActionItems(userId, List.of(latest)).getFirst();
    }

    @Transactional
    public ImpactView archive(String email, long id, boolean archived) {
        long userId = userId(email);
        visible(userId, id);
        jdbc.update("""
                UPDATE policy_recommendations SET archived_at =
                    CASE WHEN :archived THEN CURRENT_TIMESTAMP ELSE NULL END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id AND user_id = :userId
                  AND ((:archived = TRUE AND archived_at IS NULL)
                    OR (:archived = FALSE AND archived_at IS NOT NULL))
                """, new MapSqlParameterSource("archived", archived)
                .addValue("id", id).addValue("userId", userId));
        return withActionItems(userId, List.of(visible(userId, id))).getFirst();
    }

    private ImpactView visible(long userId, long id) {
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId)
                .addValue("id", id);
        return jdbc.query("SELECT r.*, p.title AS policy_title, p.agency AS policy_agency, "
                + "p.source_url AS policy_source_url, " + citationSql() + " " + VISIBLE + " AND r.id = :id", params,
                (rs, row) -> map(rs)).stream().findFirst().orElseThrow(ImpactNotFoundException::new);
    }

    /** The persisted checklist of every assessment on the page, in one query. */
    private List<ImpactView> withActionItems(long userId, List<ImpactView> impacts) {
        var items = actionItems.itemsForAssessments(userId, impacts.stream().map(ImpactView::id).toList());
        return impacts.stream().map(impact -> impact.withActionItems(items.getOrDefault(impact.id(), List.of())))
                .toList();
    }

    private ImpactView map(ResultSet rs) throws SQLException {
        ProfileImpactAssessment assessment;
        try {
            assessment = mapper.readValue(rs.getString("assessment_json"), ProfileImpactAssessment.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored impact assessment is unreadable", exception);
        }
        return new ImpactView(rs.getLong("id"), rs.getLong("policy_id"), rs.getString("policy_title"),
                rs.getString("policy_agency"), rs.getString("severity"), assessment.urgency(),
                assessment.requiresHumanReview(), assessment.summary(),
                assessment.confidence(), confidenceLabel(assessment.confidence()),
                assessment.reasons(), assessment.missingInformation(),
                citations(rs),
                List.of(), rs.getBoolean("reviewed_by_human"), instant(rs, "reviewed_at"),
                instant(rs, "archived_at") != null, instant(rs, "generated_at"),
                instant(rs, "updated_at"), rs.getInt("version"),
                "This information is not legal advice. Check the original source and a qualified adviser.");
    }

    private String citationSql() {
        return "(SELECT COALESCE(json_agg(DISTINCT refs.url ORDER BY refs.url)::text, '[]') FROM ("
                + "SELECT source_reference AS url FROM policy_source_references sr WHERE sr.policy_id = p.id "
                + "UNION SELECT source_reference AS url FROM policy_country_restrictions cr WHERE cr.policy_id = p.id"
                + ") refs) AS policy_citations";
    }

    private List<Citation> citations(ResultSet rs) throws SQLException {
        List<Citation> citations = new java.util.ArrayList<>();
        String sourceUrl = rs.getString("policy_source_url");
        citations.add(new Citation(rs.getString("policy_title"), sourceUrl));
        try {
            List<String> references = mapper.readValue(rs.getString("policy_citations"),
                    mapper.getTypeFactory().constructCollectionType(List.class, String.class));
            references.stream().filter(url -> url != null && !url.isBlank())
                    .filter(url -> !url.equals(sourceUrl))
                    .distinct().forEach(url -> citations.add(new Citation("Restriction source", url)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored policy citations are unreadable", exception);
        }
        return List.copyOf(citations);
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String orderBy(Pageable pageable) {
        String property = pageable.getSort().stream().findFirst()
                .map(org.springframework.data.domain.Sort.Order::getProperty).orElse("severity");
        boolean ascending = pageable.getSort().stream().findFirst()
                .map(org.springframework.data.domain.Sort.Order::isAscending).orElse(false);
        String direction = ascending ? "ASC" : "DESC";
        String severityRank = "CASE r.severity WHEN 'HIGH' THEN 4 "
                + "WHEN 'MEDIUM' THEN 3 WHEN 'LOW' THEN 2 ELSE 1 END";
        return switch (property) {
            case "severity" -> severityRank + " " + direction + ", r.generated_at DESC, r.id DESC";
            case "date", "createdAt" -> "r.generated_at " + direction + ", " + severityRank + " DESC, r.id DESC";
            default -> throw new IllegalArgumentException("sort must be severity or date");
        };
    }

    private String parseSeverity(String severity) {
        String normalized = severity.trim().toUpperCase(java.util.Locale.ROOT);
        if (!ImpactUrgency.labels().contains(normalized)) {
            throw new IllegalArgumentException("Unknown impact severity");
        }
        return normalized;
    }

    private String confidenceLabel(int confidence) {
        return confidence >= 80 ? "HIGH" : confidence >= 60 ? "MEDIUM" : "LOW";
    }

    private Long userId(String email) {
        return users.findByEmail(email).orElseThrow(() -> new IllegalStateException(
                "Authenticated user no longer exists")).getId();
    }

    public record Citation(String title, String url) {}
    public record ConsolidatedAssessment(ImpactUrgency netStatus, List<Citation> citations,
            List<String> uncertaintyNotes, List<ImpactView> breakdown) {}
    public record ImpactView(long id, long policyId, String policyTitle, String policyAgency,
            @io.swagger.v3.oas.annotations.media.Schema(implementation = ImpactUrgency.class) String severity, ImpactUrgency urgency, boolean requiresHumanReview,
            String summary, int confidence, String confidenceLabel,
            List<String> analysis, List<String> missingInformation, List<Citation> citations,
            List<ActionItemView> actionItems, boolean reviewedByHuman, Instant reviewedAt,
            boolean archived, Instant createdAt, Instant updatedAt, int version, String disclaimer) {
        ImpactView withActionItems(List<ActionItemView> items) {
            return new ImpactView(id, policyId, policyTitle, policyAgency, severity, urgency,
                    requiresHumanReview, summary, confidence,
                    confidenceLabel, analysis, missingInformation, citations, items, reviewedByHuman,
                    reviewedAt, archived, createdAt, updatedAt, version, disclaimer);
        }
    }
}
