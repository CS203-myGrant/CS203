package mygrant.policies;

import java.util.Arrays;
import java.util.List;

import mygrant.impact.ProfileImpactAssessmentService;
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
}
