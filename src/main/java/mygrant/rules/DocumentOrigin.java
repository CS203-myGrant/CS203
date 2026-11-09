package mygrant.rules;

/**
 * How a document reached the tracker. A court order and a news item look alike, a SECONDARY
 * source with a link to the rule and litigation words, so neither the fields nor the wording tell
 * them apart. The admin ingestion endpoint says so instead: only a document an admin posts to
 * POST /api/v1/ingestion/documents is ADMIN_RECORDED. Not stored.
 */
public enum DocumentOrigin {
    /** The news feed, Federal Register imports and syncs, and the backfill. */
    AUTOMATIC,
    /**
     * Posted by an admin, such as a court order. Once an admin has cleared a rule's dispute, only
     * such a source can dispute the rule again.
     */
    ADMIN_RECORDED
}
