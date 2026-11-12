package mygrant.impact;

/** Shared gates for owner-facing reads. Reviewer queries use their separately authorised scope. */
public final class ImpactVisibility {
    /** SQL on policy_recommendations aliased r; publication alone never bypasses review. */
    public static final String VISIBLE = """
            (r.output_type = 'ASSESSMENT' AND r.publication_status = 'PUBLISHED'
             AND coalesce(r.assessment_json::jsonb ->> 'status', 'COMPLETED') = 'COMPLETED'
             AND (r.review_status = 'APPROVED'
                  OR (r.review_status = 'NOT_REQUIRED' AND NOT r.requires_review
                      AND coalesce((r.assessment_json::jsonb ->> 'requiresHumanReview')::boolean, TRUE) = FALSE)))
            """;

    private ImpactVisibility() {}
}
