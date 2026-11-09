package mygrant.impact;

/**
 * When an owner may see an assessment: it is published and either a human reviewed it or the
 * independent judge approved it. Shared by the impact feed and the action-item checklist, which
 * must agree. V30's backfill repeats the condition in SQL because a migration cannot call Java.
 */
public final class ImpactVisibility {

    /** A SQL condition on {@code policy_recommendations} aliased {@code r}. */
    public static final String VISIBLE = "(r.output_type = 'ASSESSMENT' AND r.publication_status = 'PUBLISHED' AND (r.reviewed_by_human "
            + "OR r.assessment_json::jsonb #>> '{evaluation,verdict}' = 'YES'))";

    private ImpactVisibility() {
    }
}
