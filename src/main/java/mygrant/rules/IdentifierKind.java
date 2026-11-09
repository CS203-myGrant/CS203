package mygrant.rules;

/** What a rule identifier is. */
public enum IdentifierKind {
    /** A regulations.gov docket id such as ICEB-2025-0001, without the agency's "DHS Docket No." label. */
    DOCKET,
    /** A Regulation Identifier Number such as 1653-AA95. */
    RIN,
    /** A Federal Register document number, such as 2026-14439, that a news item links to. */
    FR_DOCUMENT
}
