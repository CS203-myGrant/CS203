package mygrant.actions;

import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import mygrant.impact.ImpactVisibility;

/**
 * The checklist generated from published impact assessments, and each owner's progress on it.
 * Depends on no impact or policy service, so the services that publish assessments can call it.
 */
@Service
public class ActionItemService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ActionItemService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
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

    private record Assessment(long userId, String severity, String json, LocalDate effectiveDate) {}
}
