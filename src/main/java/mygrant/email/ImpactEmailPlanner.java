package mygrant.email;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import mygrant.impact.ImpactUrgency;
import mygrant.impact.ImpactVisibility;

/**
 * Decides what each user should be emailed about (CS203-91), as {@link ImpactEmailAlert} rows the
 * email jobs then queue. Like the rest of the email code it runs on a schedule, apart from the
 * work that creates alerts and assessments, so nothing about email can delay or fail them.
 * <ul>
 * <li>An assessment is emailed only once its owner can see it: published straight away, or
 * approved or corrected by a reviewer. One held for review sends nothing until then.</li>
 * <li>Only changes the user has an in-app alert for are emailed, so the alert rules about who hears
 * of a policy (news never alerts, the test clock) apply to email too.</li>
 * <li>At most one FIRST item per user and policy. Until an email holds it, it follows the latest
 * visible assessment, so a correction before the digest replaces it. NOT_APPLICABLE removes it.</li>
 * <li>Once a FIRST item below HIGH has been emailed, a later visible HIGH adds one ESCALATION.</li>
 * <li>At digest time, an alerted change with no visible or held assessment (none yet, or the AI
 * was unavailable) gets a FIRST item with no urgency: "we couldn't assess this one yet".</li>
 * </ul>
 * Every write is a single conditional statement keyed on the unique (user, policy, kind), so
 * overlapping runs and instances cannot plan an item twice.
 */
@Service
public class ImpactEmailPlanner {

    private final JdbcTemplate jdbc;

    public ImpactEmailPlanner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Visible(long assessmentId, long userId, long policyId, int version, ImpactUrgency urgency,
            String summary, String title, long notificationId) {}

    /** Plans items for assessments that became visible since the cutoff; returns how many changed. */
    public int planVisible(Instant since) {
        return planVisible(since, null);
    }

    /** The same for one account only. */
    public int planVisibleFor(Long userId, Instant since) {
        return planVisible(since, userId);
    }

    private int planVisible(Instant since, Long userId) {
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(since));
        String owner = "";
        if (userId != null) {
            owner = " AND r.user_id = ?";
            args.add(userId);
        }
        // The latest visible assessment of every (user, policy) with any recent change, then only
        // those whose latest visible version itself changed in the window.
        List<Visible> visible = jdbc.query("""
                WITH latest AS (
                    SELECT DISTINCT ON (r.user_id, r.policy_id) r.id, r.user_id, r.policy_id, r.version,
                           r.severity, r.updated_at, r.assessment_json::jsonb ->> 'summary' AS summary
                    FROM policy_recommendations r
                    WHERE """ + ImpactVisibility.VISIBLE + """
                      AND (r.user_id, r.policy_id) IN (
                          SELECT changed.user_id, changed.policy_id FROM policy_recommendations changed
                          WHERE changed.updated_at >= ?)""" + owner + "\n" + """
                    ORDER BY r.user_id, r.policy_id, r.generated_at DESC, r.id DESC
                )
                SELECT l.id, l.user_id, l.policy_id, l.version, l.severity, l.summary, l.updated_at,
                       p.title, n.id AS notification_id
                FROM latest l
                JOIN policy_documents p ON p.id = l.policy_id
                JOIN notifications n ON n.user_id = l.user_id AND n.policy_id = l.policy_id
                WHERE l.updated_at >= ?
                ORDER BY l.updated_at, l.id
                """, (rs, row) -> new Visible(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("policy_id"),
                        rs.getInt("version"), ImpactUrgency.valueOf(rs.getString("severity")),
                        rs.getString("summary"), rs.getString("title"), rs.getLong("notification_id")),
                withSinceAgain(args, since));
        int changed = 0;
        for (Visible item : visible) {
            changed += plan(item);
        }
        return changed;
    }

    private int plan(Visible item) {
        if (item.urgency() == ImpactUrgency.NOT_APPLICABLE) {
            // Not applicable is never emailed; an item not yet in an email no longer applies.
            return jdbc.update("""
                    DELETE FROM impact_email_alerts
                    WHERE user_id = ? AND policy_id = ? AND kind = 'FIRST' AND email_delivery_id IS NULL
                    """, item.userId(), item.policyId());
        }
        String summary = item.summary() == null || item.summary().isBlank()
                ? "Your assessment of this policy has been updated." : item.summary();
        int changed = jdbc.update("""
                INSERT INTO impact_email_alerts (user_id, policy_id, notification_id, kind, assessment_id,
                    assessment_version, urgency, policy_title, summary)
                VALUES (?, ?, ?, 'FIRST', ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, policy_id, kind) DO UPDATE SET
                    notification_id = EXCLUDED.notification_id, assessment_id = EXCLUDED.assessment_id,
                    assessment_version = EXCLUDED.assessment_version, urgency = EXCLUDED.urgency,
                    policy_title = EXCLUDED.policy_title, summary = EXCLUDED.summary,
                    updated_at = CURRENT_TIMESTAMP
                WHERE impact_email_alerts.email_delivery_id IS NULL
                  AND (impact_email_alerts.assessment_id IS DISTINCT FROM EXCLUDED.assessment_id
                       OR impact_email_alerts.assessment_version IS DISTINCT FROM EXCLUDED.assessment_version)
                """, item.userId(), item.policyId(), item.notificationId(), item.assessmentId(), item.version(),
                item.urgency().name(), item.title(), summary);
        if (item.urgency() == ImpactUrgency.HIGH) {
            // A change already emailed below HIGH (or before it could be assessed) is now HIGH.
            changed += jdbc.update("""
                    INSERT INTO impact_email_alerts (user_id, policy_id, notification_id, kind, assessment_id,
                        assessment_version, urgency, policy_title, summary)
                    SELECT ?, ?, ?, 'ESCALATION', ?, ?, 'HIGH', ?, ?
                    WHERE EXISTS (
                        SELECT 1 FROM impact_email_alerts first
                        WHERE first.user_id = ? AND first.policy_id = ? AND first.kind = 'FIRST'
                          AND first.email_delivery_id IS NOT NULL
                          AND first.urgency IS DISTINCT FROM 'HIGH')
                    ON CONFLICT (user_id, policy_id, kind) DO NOTHING
                    """, item.userId(), item.policyId(), item.notificationId(), item.assessmentId(), item.version(),
                    item.title(), summary, item.userId(), item.policyId());
        }
        return changed;
    }

    /**
     * Plans digest items for alerted changes since the cutoff that have no visible or held
     * assessment: none was made yet, or the AI was unavailable. Returns how many it added.
     */
    public int planUnassessed(Instant since) {
        return planUnassessed(since, null);
    }

    public int planUnassessedFor(Long userId, Instant since) {
        return planUnassessed(since, userId);
    }

    private int planUnassessed(Instant since, Long userId) {
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(since));
        String owner = "";
        if (userId != null) {
            owner = " AND n.user_id = ?";
            args.add(userId);
        }
        return jdbc.update("""
                INSERT INTO impact_email_alerts (user_id, policy_id, notification_id, kind, urgency,
                    policy_title, summary)
                SELECT n.user_id, n.policy_id, n.id, 'FIRST', NULL, p.title, n.message
                FROM notifications n JOIN policy_documents p ON p.id = n.policy_id
                WHERE n.read_at IS NULL AND n.created_at >= ?""" + owner + "\n" + """
                  AND NOT EXISTS (
                      SELECT 1 FROM policy_recommendations r
                      WHERE r.user_id = n.user_id AND r.policy_id = n.policy_id AND r.output_type = 'ASSESSMENT'
                        AND (r.publication_status = 'PENDING_REVIEW' OR """ + ImpactVisibility.VISIBLE + """
                      ))
                ON CONFLICT (user_id, policy_id, kind) DO NOTHING
                """, args.toArray());
    }

    private static Object[] withSinceAgain(List<Object> args, Instant since) {
        List<Object> all = new ArrayList<>(args);
        all.add(Timestamp.from(since));
        return all.toArray();
    }
}
