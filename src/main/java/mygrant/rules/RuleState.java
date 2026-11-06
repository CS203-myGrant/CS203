package mygrant.rules;

import java.time.LocalDate;

import mygrant.policies.PolicyStatus;

/** A rule's primary-source stage and effective date, and whether news reports it disputed. */
public record RuleState(PolicyStatus primaryStage, LocalDate effectiveDate, boolean disputed) {
}
