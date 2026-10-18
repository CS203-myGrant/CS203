package mygrant.ingestion.federalregister;

import java.time.LocalDate;
import java.util.List;

import mygrant.ingestion.dto.IngestionResponse;
import mygrant.impact.ProfileImpactAssessment;

/** Import result including explainable visa classification and indexing status. */
public record FederalRegisterIngestionResponse(
        String documentNumber,
        String title,
        String type,
        String agency,
        LocalDate publicationDate,
        LocalDate effectiveDate,
        String sourceUrl,
        boolean affectsF1,
        boolean affectsJ1,
        boolean affectsH1b,
        List<String> detectedVisaTypes,
        List<String> matchedSignals,
        IngestionResponse ingestion,
        ProfileImpactAssessment impactAssessment
) {
}
