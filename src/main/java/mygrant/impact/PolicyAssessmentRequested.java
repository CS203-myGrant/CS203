package mygrant.impact;

/** Published inside the ingestion transaction; handled only after successful commit. */
public record PolicyAssessmentRequested(Long policyId) {}
