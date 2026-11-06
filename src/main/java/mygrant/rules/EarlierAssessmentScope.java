package mygrant.rules;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Users with a policy_recommendations row for another document of the same rule published on or
 * before the one that caused the change: the people whose advice the change has made stale.
 * Deactivated accounts are left out.
 */
@Component
public class EarlierAssessmentScope implements RuleReassessmentScope {

    private final NamedParameterJdbcTemplate jdbc;

    public EarlierAssessmentScope(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> usersToReassess(PolicyChangedEvent event) {
        return jdbc.queryForList("""
                SELECT DISTINCT u.email
                FROM policy_recommendations r
                JOIN policy_rule_documents d ON d.policy_id = r.policy_id
                JOIN policy_documents p ON p.id = r.policy_id
                JOIN users u ON u.id = r.user_id
                WHERE d.rule_id = :ruleId AND r.policy_id <> :sourceId
                  AND p.publication_date <= :published AND u.active
                ORDER BY u.email
                """, new MapSqlParameterSource(Map.of("ruleId", event.ruleId(),
                        "sourceId", event.source().policyId(), "published", event.source().publishedOn())),
                String.class);
    }
}
