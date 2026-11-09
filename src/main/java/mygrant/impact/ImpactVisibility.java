package mygrant.impact;

/**
 * When an owner may see an assessment: it has a successful generator result and is published.
 * A non-passing independent review remains visible with an explicit unverified flag.
 */
public final class ImpactVisibility {

    /** A SQL condition on {@code policy_recommendations} aliased {@code r}. */
    public static final String VISIBLE = "(r.output_type = 'ASSESSMENT' AND r.publication_status = 'PUBLISHED')";

    private ImpactVisibility() {
    }
}
