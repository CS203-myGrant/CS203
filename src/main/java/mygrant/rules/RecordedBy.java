package mygrant.rules;

/** What wrote a history entry. Backfilled entries never raise events or alerts. */
public enum RecordedBy {
    INGESTION,
    BACKFILL
}
