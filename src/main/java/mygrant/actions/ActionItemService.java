package mygrant.actions;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import mygrant.impact.ImpactVisibility;
import mygrant.testclock.TestClock;
import mygrant.user.UserRepository;

/**
 * The checklist generated from published impact assessments, and each owner's progress on it.
 * Depends on no impact or policy service, so the services that publish assessments can call it.
 */
@Service
public class ActionItemService {
    private static final int URGENT_DAYS = 7;
    private static final String FROM = """
             FROM action_items a
            JOIN policy_recommendations r ON r.id = a.assessment_id
            JOIN policy_documents p ON p.id = r.policy_id
            """;
    private static final String SELECT = "SELECT a.*, r.policy_id, p.title AS policy_title" + FROM;
    private static final String CHECKLIST_ORDER = """
             ORDER BY a.deadline ASC NULLS LAST,
                CASE a.priority WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END,
                a.assessment_id, a.position, a.id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final UserRepository users;
    private final TestClock clock;

    public ActionItemService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, UserRepository users,
            TestClock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.users = users;
        this.clock = clock;
    }

    /**
     * Brings an assessment's items in line with its recommended actions, one item per action in
     * order. A new action gets a new item; an untouched (PENDING) item whose action is gone is
     * removed. An item that already exists keeps its status, so a dismissed one never comes back,
     * and started, completed or dismissed items are never removed. Does nothing unless the owner
     * can see the assessment. Call it in the transaction that publishes or revises the assessment.
     */
    @Transactional
    public void syncForAssessment(long assessmentId) {
        List<Assessment> found = jdbc.query("""
                SELECT r.user_id, r.severity, r.assessment_json, p.effective_date
                FROM policy_recommendations r JOIN policy_documents p ON p.id = r.policy_id
                WHERE r.id = :id AND """ + ImpactVisibility.VISIBLE,
                Map.of("id", assessmentId), (rs, row) -> new Assessment(rs.getLong("user_id"),
                        rs.getString("severity"), rs.getString("assessment_json"),
                        rs.getObject("effective_date", LocalDate.class)));
        if (found.isEmpty()) {
            return;
        }
        Assessment assessment = found.getFirst();
        ActionPriority priority = ActionPriority.fromSeverity(assessment.severity());
        // Keyed by item_key: the first of two actions that normalise alike wins, as in the backfill.
        Map<String, MapSqlParameterSource> items = new LinkedHashMap<>();
        List<String> actions = recommendedActions(assessment.json());
        for (int index = 0; index < actions.size(); index++) {
            String action = actions.get(index);
            if (action == null || ActionItemText.trim(action).isEmpty()) {
                continue;
            }
            String key = ActionItemText.key(action);
            if (items.containsKey(key)) {
                continue;
            }
            items.put(key, new MapSqlParameterSource()
                    .addValue("assessmentId", assessmentId)
                    .addValue("userId", assessment.userId())
                    .addValue("key", key)
                    .addValue("position", index + 1)
                    .addValue("title", ActionItemText.title(action))
                    .addValue("description", ActionItemText.description(action), Types.VARCHAR)
                    .addValue("priority", priority.name())
                    .addValue("deadline", assessment.effectiveDate(), Types.DATE));
        }

        MapSqlParameterSource removal = new MapSqlParameterSource("id", assessmentId);
        String dropped = "";
        if (!items.isEmpty()) {
            removal.addValue("keys", List.copyOf(items.keySet()));
            dropped = " AND item_key NOT IN (:keys)";
        }
        jdbc.update("DELETE FROM action_items WHERE assessment_id = :id AND status = 'PENDING'" + dropped, removal);
        if (!items.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO action_items
                        (assessment_id, user_id, item_key, position, title, description, priority, deadline)
                    VALUES (:assessmentId, :userId, :key, :position, :title, :description, :priority, :deadline)
                    ON CONFLICT (assessment_id, item_key) DO NOTHING
                    """, items.values().toArray(MapSqlParameterSource[]::new));
        }
    }

    /**
     * The caller's items from assessments they can see, by deadline (undated last), then priority.
     * Without a status filter: only PENDING and IN_PROGRESS items of unarchived assessments.
     */
    @Transactional(readOnly = true)
    public Page<ActionItemView> list(String email, Pageable pageable, Collection<ActionStatus> statuses,
            Collection<ActionPriority> priorities) {
        if (pageable.getPageSize() > 100 || pageable.getPageSize() < 1) {
            throw new IllegalArgumentException("Page size must be between 1 and 100");
        }
        long userId = userId(email);
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId);
        String where = " WHERE a.user_id = :userId AND " + ImpactVisibility.VISIBLE;
        List<String> statusNames = names(statuses);
        if (statusNames.isEmpty()) {
            where += " AND a.status IN ('PENDING', 'IN_PROGRESS') AND r.archived_at IS NULL";
        } else {
            where += " AND a.status IN (:statuses)";
            params.addValue("statuses", statusNames);
        }
        List<String> priorityNames = names(priorities);
        if (!priorityNames.isEmpty()) {
            where += " AND a.priority IN (:priorities)";
            params.addValue("priorities", priorityNames);
        }
        Long total = jdbc.queryForObject("SELECT count(*)" + FROM + where, params, Long.class);
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        LocalDate today = clock.today(userId);
        List<ActionItemView> rows = jdbc.query(SELECT + where + CHECKLIST_ORDER + " LIMIT :limit OFFSET :offset",
                params, (rs, row) -> map(rs, today));
        // The order is fixed, so a requested sort is ignored rather than echoed back.
        return new PageImpl<>(rows, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()),
                total == null ? 0 : total);
    }

    /**
     * 404 when the item is missing or its assessment is hidden from its owner; 403 when the item
     * is visible but belongs to someone else. Repeating the current status changes nothing.
     */
    @Transactional
    public ActionItemView updateStatus(String email, long id, ActionStatus status) {
        long userId = userId(email);
        List<Long> owners = jdbc.query("SELECT a.user_id" + FROM + " WHERE a.id = :id AND "
                + ImpactVisibility.VISIBLE + " FOR UPDATE OF a", Map.of("id", id), (rs, row) -> rs.getLong(1));
        if (owners.isEmpty()) {
            throw new ActionItemNotFoundException();
        }
        if (owners.getFirst() != userId) {
            throw new ActionItemAccessDeniedException();
        }
        // Leaving COMPLETED or DISMISSED clears its stamp, which the table's CHECKs require.
        jdbc.update("""
                UPDATE action_items SET status = :status,
                    completed_at = CASE WHEN :status = 'COMPLETED' THEN CURRENT_TIMESTAMP END,
                    dismissed_at = CASE WHEN :status = 'DISMISSED' THEN CURRENT_TIMESTAMP END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id AND status <> :status
                """, new MapSqlParameterSource("id", id).addValue("status", status.name()));
        LocalDate today = clock.today(userId);
        return jdbc.query(SELECT + " WHERE a.id = :id", Map.of("id", id), (rs, row) -> map(rs, today)).getFirst();
    }

    /** Every item of these assessments, whatever its status, by position: one query for a feed page. */
    @Transactional(readOnly = true)
    public Map<Long, List<ActionItemView>> itemsForAssessments(long userId, Collection<Long> assessmentIds) {
        if (assessmentIds.isEmpty()) {
            return Map.of();
        }
        LocalDate today = clock.today(userId);
        return jdbc.query(SELECT + " WHERE a.user_id = :userId AND a.assessment_id IN (:ids)"
                        + " ORDER BY a.assessment_id, a.position, a.id",
                new MapSqlParameterSource("userId", userId).addValue("ids", List.copyOf(assessmentIds)),
                (rs, row) -> map(rs, today)).stream()
                .collect(Collectors.groupingBy(ActionItemView::assessmentId, LinkedHashMap::new, Collectors.toList()));
    }

    /** For AccountPurgeService: users are anonymised, never deleted, so nothing cascades. */
    @Transactional
    public void deleteAllByUserId(long userId) {
        jdbc.update("DELETE FROM action_items WHERE user_id = :userId", Map.of("userId", userId));
    }

    private List<String> recommendedActions(String json) {
        JsonNode actions;
        try {
            actions = mapper.readTree(json).path("recommendedActions");
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored impact assessment is unreadable", exception);
        }
        List<String> texts = new ArrayList<>();
        if (actions.isArray()) {
            actions.forEach(action -> texts.add(action.isNull() ? null : action.asText()));
        }
        return texts;
    }

    private ActionItemView map(ResultSet rs, LocalDate today) throws SQLException {
        ActionStatus status = ActionStatus.valueOf(rs.getString("status"));
        LocalDate deadline = rs.getObject("deadline", LocalDate.class);
        boolean due = status.isActive() && deadline != null;
        String title = rs.getString("title");
        String description = rs.getString("description");
        return new ActionItemView(rs.getLong("id"), rs.getLong("assessment_id"), rs.getLong("policy_id"),
                rs.getString("policy_title"), title, description, ActionPriority.valueOf(rs.getString("priority")),
                status, deadline, due && !deadline.isAfter(today.plusDays(URGENT_DAYS)),
                due && deadline.isBefore(today), instant(rs, "completed_at"), instant(rs, "dismissed_at"),
                instant(rs, "created_at"), instant(rs, "updated_at"), description == null ? title : description);
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static List<String> names(Collection<? extends Enum<?>> values) {
        return values == null ? List.of()
                : values.stream().filter(Objects::nonNull).map(Enum::name).distinct().toList();
    }

    private long userId(String email) {
        return users.findByEmail(email).orElseThrow(() -> new IllegalStateException(
                "Authenticated user no longer exists")).getId();
    }

    private record Assessment(long userId, String severity, String json, LocalDate effectiveDate) {}
}
