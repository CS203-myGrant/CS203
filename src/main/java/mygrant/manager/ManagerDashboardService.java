package mygrant.manager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mygrant.impact.ImpactReviewService;
import mygrant.impact.ImpactVisibility;
import mygrant.manager.dto.ManagerDashboardResponse;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

@Service
public class ManagerDashboardService {
    private final NamedParameterJdbcTemplate jdbc;
    private final UserRepository users;
    private final ImpactReviewService impactReview;

    public ManagerDashboardService(NamedParameterJdbcTemplate jdbc, UserRepository users,
            ImpactReviewService impactReview) {
        this.jdbc = jdbc;
        this.users = users;
        this.impactReview = impactReview;
    }

    @Transactional(readOnly = true)
    public ManagerDashboardResponse dashboard(String managerEmail) {
        User manager = users.findByEmail(managerEmail)
                .orElseThrow(() -> new IllegalStateException("Authenticated manager no longer exists"));
        if (manager.getRole() != UserRole.MANAGER) {
            throw new IllegalStateException("Authenticated user is not a manager");
        }

        var params = new MapSqlParameterSource("managerId", manager.getId());
        Long linked = jdbc.queryForObject("""
                SELECT count(*)
                FROM manager_user_memberships m
                JOIN users u ON u.id = m.user_id
                WHERE m.manager_id = :managerId AND u.role = 'APPLICANT' AND u.active = TRUE
                """, params, Long.class);

        Map<String, Long> visaCounts = counts("""
                SELECT coalesce(nullif(upper(trim(u.visa_type)), ''), 'UNKNOWN') AS bucket,
                       count(DISTINCT u.id) AS total
                FROM manager_user_memberships m
                JOIN users u ON u.id = m.user_id
                WHERE m.manager_id = :managerId AND u.role = 'APPLICANT' AND u.active = TRUE
                GROUP BY coalesce(nullif(upper(trim(u.visa_type)), ''), 'UNKNOWN')
                ORDER BY bucket
                """, params);

        Map<String, Long> severityCounts = counts("""
                SELECT r.severity AS bucket, count(*) AS total
                FROM manager_user_memberships m
                JOIN users u ON u.id = m.user_id
                JOIN policy_recommendations r ON r.user_id = u.id
                WHERE m.manager_id = :managerId AND u.role = 'APPLICANT' AND u.active = TRUE
                  AND """ + ImpactVisibility.VISIBLE + """
                  AND r.archived_at IS NULL
                GROUP BY r.severity
                ORDER BY CASE r.severity WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2
                         WHEN 'MEDIUM' THEN 3 WHEN 'LOW' THEN 4 ELSE 5 END
                """, params);

        return new ManagerDashboardResponse(linked == null ? 0 : linked, visaCounts, severityCounts,
                impactReview.pending(managerEmail));
    }

    private Map<String, Long> counts(String sql, MapSqlParameterSource params) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query(sql, params, (RowCallbackHandler) rs -> {
            counts.put(rs.getString("bucket"), rs.getLong("total"));
        });
        return Collections.unmodifiableMap(counts);
    }
}
