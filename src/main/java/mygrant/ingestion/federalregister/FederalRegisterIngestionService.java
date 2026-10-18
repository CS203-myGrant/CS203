package mygrant.ingestion.federalregister;

import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import mygrant.ingestion.PolicyIngestionService;
import mygrant.ingestion.classification.VisaClassification;
import mygrant.ingestion.classification.VisaTypeClassifier;
import mygrant.ingestion.dto.IngestPolicyRequest;
import mygrant.ingestion.dto.IngestionResponse;
import mygrant.impact.ProfileImpactAssessment;
import mygrant.impact.ProfileImpactAssessmentService;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;

/** Orchestrates Federal Register download, classification, and policy indexing. */
@Service
public class FederalRegisterIngestionService {

    // Long Federal Register rules can exceed a million characters. Keep the
    // original full text in PostgreSQL, but index the opening summary and
    // effective-date discussion for the first demo's focused RAG queries.
    private static final int MAX_INDEX_CHARACTERS = 6_000;

    private final FederalRegisterClient client;
    private final VisaTypeClassifier classifier;
    private final PolicyIngestionService ingestionService;
    private final ProfileImpactAssessmentService impactAssessmentService;

    public FederalRegisterIngestionService(FederalRegisterClient client,
            VisaTypeClassifier classifier, PolicyIngestionService ingestionService,
            ProfileImpactAssessmentService impactAssessmentService) {
        this.client = client;
        this.classifier = classifier;
        this.ingestionService = ingestionService;
        this.impactAssessmentService = impactAssessmentService;
    }

    /** Imports a published document by number; repeat imports are idempotent. */
    public FederalRegisterIngestionResponse ingest(String documentNumber, String userEmail) {
        FederalRegisterDocument document = client.getDocument(documentNumber);
        if (document == null) {
            throw new IllegalArgumentException("Federal Register document was not found: " + documentNumber);
        }

        String content = client.downloadFullText(document.rawTextUrl());
        VisaClassification classification = classifier.classify(document.title(), content);
        if (classification.visaTypes().isEmpty()) {
            throw new IllegalArgumentException(
                    "No supported visa category was detected; document was not ingested");
        }

        IngestionResponse ingestion = ingestionService.ingest(new IngestPolicyRequest(
                document.documentNumber(),
                document.title(),
                agencyNames(document),
                String.join(",", classification.visaTypes()),
                mapStatus(document.type()),
                SourceType.PRIMARY,
                document.publicationDate(),
                document.effectiveDate(),
                document.htmlUrl(),
                content), content.substring(0, Math.min(content.length(), MAX_INDEX_CHARACTERS)));

        String agency = agencyNames(document);
        ProfileImpactAssessment impactAssessment = impactAssessmentService.assess(
                userEmail, document, agency, content, classification);

        return new FederalRegisterIngestionResponse(
                document.documentNumber(), document.title(), document.type(), agency,
                document.publicationDate(), document.effectiveDate(), document.htmlUrl(),
                classification.affectsF1(), classification.affectsJ1(),
                classification.affectsH1b(), classification.visaTypes(),
                classification.matchedSignals(), ingestion, impactAssessment);
    }

    private String agencyNames(FederalRegisterDocument document) {
        if (document.agencies() == null || document.agencies().isEmpty()) {
            return "Unknown Federal Agency";
        }
        return document.agencies().stream().map(FederalRegisterDocument.Agency::name)
                .distinct().collect(Collectors.joining("; "));
    }

    private PolicyStatus mapStatus(String type) {
        if ("Rule".equalsIgnoreCase(type)) {
            return PolicyStatus.FINAL;
        }
        if ("Proposed Rule".equalsIgnoreCase(type)) {
            return PolicyStatus.PROPOSED;
        }
        return PolicyStatus.UNKNOWN;
    }
}
