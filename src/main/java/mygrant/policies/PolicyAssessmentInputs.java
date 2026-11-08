package mygrant.policies;

import java.util.Arrays;
import java.util.List;

import mygrant.impact.ProfileImpactAssessmentService;
import mygrant.ingestion.classification.EntryRestrictionExtractor.Extraction;
import mygrant.ingestion.classification.VisaClassification;
import mygrant.ingestion.federalregister.FederalRegisterDocument;

/**
 * What {@link ProfileImpactAssessmentService#assess} needs for a stored policy. Shared by the
 * on-demand assessment and rule-change reassessment (CS203-86), so both assess the same input.
 */
public final class PolicyAssessmentInputs {

    private PolicyAssessmentInputs() {
    }

    public static FederalRegisterDocument document(PolicyDocument policy) {
        return new FederalRegisterDocument(policy.getExternalId(), policy.getTitle(),
                policy.getStatus().name(), policy.getPublicationDate(), policy.getEffectiveDate(),
                policy.getSourceUrl(), null, List.of());
    }

    public static VisaClassification classification(PolicyDocument policy) {
        List<String> visas = policy.getVisaType() == null ? List.of()
                : Arrays.stream(policy.getVisaType().split(",")).map(String::trim).toList();
        return new VisaClassification(visas.contains("F-1"), visas.contains("J-1"),
                visas.contains("H-1B"), visas, List.of());
    }

    /** Shared structured context for stored policies and freshly extracted Federal Register sources. */
    public static String restrictionContext(PolicyDocument policy) {
        String rules = policy.getCountryRestrictions().stream()
                .map(rule -> "country=" + rule.getCountryCode() + ", scope=" + rule.getScope()
                        + ", source=" + rule.getSourceReference() + ", exemptions=" + rule.getExemptionWording())
                .collect(java.util.stream.Collectors.joining("\n"));
        return restrictionContext(policy.getAffectedCountries(), policy.getRestrictionCategories(),
                policy.getSourceReferences(), rules, policy.hasSpecialDocumentHolderRule());
    }

    public static String restrictionContext(Extraction extraction) {
        String rules = extraction.restrictions().stream()
                .map(rule -> "country=" + rule.getCountryCode() + ", scope=" + rule.getScope()
                        + ", source=" + rule.getSourceReference() + ", exemptions=" + rule.getExemptionWording())
                .collect(java.util.stream.Collectors.joining("\n"));
        return restrictionContext(new java.util.LinkedHashSet<>(extraction.affectedCountries()),
                new java.util.LinkedHashSet<>(extraction.categories()), extraction.sourceReferences(), rules,
                extraction.specialDocumentHolderRule());
    }

    private static String restrictionContext(java.util.Set<String> countries,
            java.util.Set<RestrictionCategory> categories, List<String> references,
            String rules, boolean specialDocumentHolderRule) {
        return "affectedCountries: " + countries + "\nrestrictionCategories: " + categories
                + "\nsourceReferences: " + references + "\ncountryRules:\n"
                + (rules.isBlank() ? "none" : rules)
                + "\nspecialDocumentHolderRule: " + specialDocumentHolderRule;
    }
}
