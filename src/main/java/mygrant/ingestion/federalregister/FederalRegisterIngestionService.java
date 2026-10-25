package mygrant.ingestion.federalregister;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import mygrant.ingestion.PolicyIngestionService;
import mygrant.ingestion.connector.ConnectorFetch;
import mygrant.ingestion.connector.ConnectorHealth;
import mygrant.ingestion.connector.ContentHash;
import mygrant.ingestion.connector.DataConnector;
import mygrant.ingestion.connector.SourceIdentity;
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
public class FederalRegisterIngestionService
        implements DataConnector<FederalRegisterImportRequest,
                FederalRegisterIngestionService.FetchedDocument,
                FederalRegisterIngestionService.PreparedDocument,
                FederalRegisterIngestionResponse> {

    // Long Federal Register rules can exceed a million characters. Keep the
    // original full text in PostgreSQL, but index the opening summary and
    // effective-date discussion for the first demo's focused RAG queries.
    private static final int MAX_INDEX_CHARACTERS = 6_000;
    private static final SourceIdentity SOURCE = new SourceIdentity("FEDERAL_REGISTER", SourceType.PRIMARY);

    private final FederalRegisterClient client;
    private final VisaTypeClassifier classifier;
    private final PolicyIngestionService ingestionService;
    private final ProfileImpactAssessmentService impactAssessmentService;
    private final AtomicReference<ConnectorHealth> currentHealth =
            new AtomicReference<>(ConnectorHealth.notChecked(SOURCE));

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
        return ingest(new FederalRegisterImportRequest(documentNumber, userEmail));
    }

    @Override
    public SourceIdentity source() {
        return SOURCE;
    }

    @Override
    public ConnectorFetch<FetchedDocument> fetch(FederalRegisterImportRequest request) {
        FederalRegisterDocument document = client.getDocument(request.documentNumber());
        if (document == null) {
            throw new IllegalArgumentException("Federal Register document was not found: " + request.documentNumber());
        }

        String content = client.downloadFullText(document.rawTextUrl());
        return new ConnectorFetch<>(new FetchedDocument(document, content), document.htmlUrl(),
                Instant.now(), ContentHash.sha256(content));
    }

    @Override
    public PreparedDocument transform(ConnectorFetch<FetchedDocument> fetched,
            FederalRegisterImportRequest request) {
        FederalRegisterDocument document = fetched.payload().document();
        String content = fetched.payload().content();
        VisaClassification classification = classifier.classify(document.title(), content);
        if (classification.visaTypes().isEmpty()) {
            throw new IllegalArgumentException(
                    "No supported visa category was detected; document was not ingested");
        }
        return new PreparedDocument(document, content, classification, agencyNames(document));
    }

    @Override
    public FederalRegisterIngestionResponse persistDeduplicated(PreparedDocument prepared,
            FederalRegisterImportRequest request) {
        FederalRegisterDocument document = prepared.document();
        String content = prepared.content();
        VisaClassification classification = prepared.classification();
        IngestionResponse ingestion = ingestionService.ingest(new IngestPolicyRequest(
                document.documentNumber(),
                document.title(),
                prepared.agency(),
                String.join(",", classification.visaTypes()),
                mapStatus(document.type()),
                source().type(),
                document.publicationDate(),
                document.effectiveDate(),
                document.htmlUrl(),
                content), content.substring(0, Math.min(content.length(), MAX_INDEX_CHARACTERS)));

        ProfileImpactAssessment impactAssessment = request.assessImpact()
                ? impactAssessmentService.assess(request.userEmail(), document, prepared.agency(), content, classification)
                : null;

        return new FederalRegisterIngestionResponse(
                document.documentNumber(), document.title(), document.type(), prepared.agency(),
                document.publicationDate(), document.effectiveDate(), document.htmlUrl(),
                classification.affectsF1(), classification.affectsJ1(),
                classification.affectsH1b(), classification.visaTypes(),
                classification.matchedSignals(), ingestion, impactAssessment);
    }

    @Override
    public ConnectorHealth health() {
        return currentHealth.get();
    }

    @Override
    public FederalRegisterIngestionResponse ingest(FederalRegisterImportRequest request) {
        Instant attemptAt = Instant.now();
        ConnectorHealth.Stage stage = ConnectorHealth.Stage.FETCH;
        try {
            ConnectorFetch<FetchedDocument> fetched = fetch(request);
            stage = ConnectorHealth.Stage.TRANSFORM;
            PreparedDocument prepared = transform(fetched, request);
            stage = ConnectorHealth.Stage.PERSIST;
            FederalRegisterIngestionResponse result = persistDeduplicated(prepared, request);
            currentHealth.updateAndGet(previous -> previous.succeeded(attemptAt, Instant.now()));
            return result;
        } catch (RuntimeException exception) {
            ConnectorHealth.Stage failedStage = stage;
            currentHealth.updateAndGet(previous -> previous.failed(attemptAt, failedStage, exception));
            throw exception;
        }
    }

    public record FetchedDocument(FederalRegisterDocument document, String content) {}

    public record PreparedDocument(FederalRegisterDocument document, String content,
            VisaClassification classification, String agency) {}

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
