package mygrant.impact;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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

import mygrant.user.UserRepository;

/** Published, owner-scoped impact assessments built on existing policy recommendations. */
@Service
public class ImpactFeedService {
    private static final String VISIBLE = """
            FROM policy_recommendations r JOIN policy_documents p ON p.id = r.policy_id
            WHERE r.user_id = :userId AND r.publication_status = 'PUBLISHED'
              AND (r.reviewed_by_human OR r.assessment_json::jsonb #>> '{evaluation,verdict}' = 'YES')
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final UserRepository users;

    public ImpactFeedService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, UserRepository users) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public Page<ImpactView> list(String email, Pageable pageable, String severity, boolean archived) {
        if (pageable.getPageSize() > 100 || pageable.getPageSize() < 1) {
            throw new IllegalArgumentException("Page size must be between 1 and 100");
        }
        String order = orderBy(pageable);
        String where = VISIBLE + (archived ? " AND r.archived_at IS NOT NULL" : " AND r.archived_at IS NULL");
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId(email))
                .addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        if (severity != null && !severity.isBlank()) {
            params.addValue("severity", parseSeverity(severity));
            where += " AND r.severity = :severity";
        }
        Long total = jdbc.queryForObject("SELECT count(*) " + where, params, Long.class);
        List<ImpactView> rows = jdbc.query("SELECT r.*, p.title AS policy_title, p.agency AS policy_agency, "
                + "p.source_url AS policy_source_url " + where + " ORDER BY " + order
                + " LIMIT :limit OFFSET :offset", params, (rs, row) -> map(rs));
        return new PageImpl<>(rows, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                pageable.getSort()), total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public ImpactView get(String email, long id) {
        return visible(email, id);
    }

    @Transactional
    public ImpactView archive(String email, long id, boolean archived) {
        visible(email, id);
        jdbc.update("""
                UPDATE policy_recommendations SET archived_at =
                    CASE WHEN :archived THEN CURRENT_TIMESTAMP ELSE NULL END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id AND user_id = :userId
                  AND ((:archived = TRUE AND archived_at IS NULL)
                    OR (:archived = FALSE AND archived_at IS NOT NULL))
                """, new MapSqlParameterSource("archived", archived)
                .addValue("id", id).addValue("userId", userId(email)));
        return visible(email, id);
    }

    private ImpactView visible(String email, long id) {
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId(email))
                .addValue("id", id);
        return jdbc.query("SELECT r.*, p.title AS policy_title, p.agency AS policy_agency, "
                + "p.source_url AS policy_source_url " + VISIBLE + " AND r.id = :id", params,
                (rs, row) -> map(rs)).stream().findFirst().orElseThrow(ImpactNotFoundException::new);
    }

    private ImpactView map(ResultSet rs) throws SQLException {
        ProfileImpactAssessment assessment;
        try {
            assessment = mapper.readValue(rs.getString("assessment_json"), ProfileImpactAssessment.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored impact assessment is unreadable", exception);
        }
        long id = rs.getLong("id");
        List<ActionItem> actions = java.util.stream.IntStream.range(0, assessment.recommendedActions().size())
                .mapToObj(index -> new ActionItem(id + ":" + (index + 1),
                        assessment.recommendedActions().get(index))).toList();
        return new ImpactView(id, rs.getLong("policy_id"), rs.getString("policy_title"),
                rs.getString("policy_agency"), rs.getString("severity"), assessment.summary(),
                assessment.confidence(), confidenceLabel(assessment.confidence()),
                assessment.reasons(), assessment.missingInformation(),
                List.of(new Citation(rs.getString("policy_title"), rs.getString("policy_source_url"))),
                actions, rs.getBoolean("reviewed_by_human"), instant(rs, "reviewed_at"),
                instant(rs, "archived_at") != null, instant(rs, "generated_at"),
                instant(rs, "updated_at"), rs.getInt("version"),
                "This information is not legal advice. Check the original source and a qualified adviser.");
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
        String severityRank = "CASE r.severity WHEN 'CRITICAL' THEN 5 WHEN 'HIGH' THEN 4 "
                + "WHEN 'MEDIUM' THEN 3 WHEN 'LOW' THEN 2 ELSE 1 END";
        return switch (property) {
            case "severity" -> severityRank + " " + direction + ", r.generated_at DESC, r.id DESC";
            case "date", "createdAt" -> "r.generated_at " + direction + ", " + severityRank + " DESC, r.id DESC";
            default -> throw new IllegalArgumentException("sort must be severity or date");
        };
    }

    private String parseSeverity(String severity) {
        String normalized = severity.trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO").contains(normalized)) {
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
    public record ActionItem(String id, String text) {}
    public record ImpactView(long id, long policyId, String policyTitle, String policyAgency,
            String severity, String summary, int confidence, String confidenceLabel,
            List<String> analysis, List<String> missingInformation, List<Citation> citations,
            List<ActionItem> actionItems, boolean reviewedByHuman, Instant reviewedAt,
            boolean archived, Instant createdAt, Instant updatedAt, int version, String disclaimer) {}
}
