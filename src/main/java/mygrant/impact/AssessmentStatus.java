package mygrant.impact;

public enum AssessmentStatus {
    COMPLETED, UNAVAILABLE,
    /**
     * API only: the assessment exists but a reviewer must check it before its owner sees it.
     * Never stored; the stored draft keeps COMPLETED and its publication state says it is held.
     */
    AWAITING_REVIEW
}
