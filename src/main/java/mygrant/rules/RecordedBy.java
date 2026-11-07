package mygrant.rules;

/** What wrote a history entry. Backfilled and admin entries never raise events or alerts. */
public enum RecordedBy {
    INGESTION,
    BACKFILL,
    /** An admin cleared the rule's dispute after a court lifted the block. */
    ADMIN
}
