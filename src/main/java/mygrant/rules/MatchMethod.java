package mygrant.rules;

/** How a document was put in its rule. */
public enum MatchMethod {
    /** The document started the rule. */
    NEW_RULE,
    DOCKET,
    RIN,
    DOCKET_AND_RIN,
    /** A news item links to a Federal Register document already in the rule. */
    FR_DOCUMENT,
    /** No identifier matched; the title is similar. Always flagged for review. */
    TITLE
}
