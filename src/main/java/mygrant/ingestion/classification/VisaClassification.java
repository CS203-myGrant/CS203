package mygrant.ingestion.classification;

import java.util.List;

/** Classification flags and evidence signals for the supported visa types. */
public record VisaClassification(
        boolean affectsF1,
        boolean affectsJ1,
        boolean affectsH1b,
        List<String> visaTypes,
        List<String> matchedSignals
) {
}
