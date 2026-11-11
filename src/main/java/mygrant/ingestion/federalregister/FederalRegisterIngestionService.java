package mygrant.ingestion.federalregister;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import mygrant.ingestion.PolicyIngestionService;
import mygrant.ingestion.connector.ConnectorFetch;
import mygrant.ingestion.connector.ConnectorHealth;
import mygrant.ingestion.connector.ContentHash;
import mygrant.ingestion.connector.DataConnector;
import mygrant.ingestion.connector.SourceIdentity;
import mygrant.ingestion.classification.VisaClassification;
import mygrant.ingestion.classification.VisaTypeClassifier;
import mygrant.ingestion.classification.EntryRestrictionExtractor;
import mygrant.ingestion.dto.IngestPolicyRequest;
import mygrant.ingestion.dto.IngestionResponse;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;
import mygrant.rules.RuleIdentifiers;

/** Orchestrates Federal Register download, classification, and policy indexing. */
@Service
public class FederalRegisterIngestionService
        implements DataConnector<FederalRegisterImportRequest,
                FederalRegisterIngestionService.FetchedDocument,
                FederalRegisterIngestionService.PreparedDocument,
                FederalRegisterIngestionResponse> {

    private static final SourceIdentity SOURCE = new SourceIdentity("FEDERAL_REGISTER", SourceType.PRIMARY);
    private static final String PRESIDENTIAL_DOCUMENT = "Presidential Document";
    private static final EntryRestrictionExtractor.Extraction NO_RESTRICTIONS =
            new EntryRestrictionExtractor.Extraction(false, false, false, List.of(), List.of(), List.of(), List.of());

    private final FederalRegisterClient client;
    private final VisaTypeClassifier classifier;
    private final EntryRestrictionExtractor restrictionExtractor;
    private final PolicyIngestionService ingestionService;
    private final AtomicReference<ConnectorHealth> currentHealth =
            new AtomicReference<>(ConnectorHealth.notChecked(SOURCE));

    @Autowired
    public FederalRegisterIngestionService(FederalRegisterClient client,
            VisaTypeClassifier classifier, EntryRestrictionExtractor restrictionExtractor, PolicyIngestionService ingestionService) {
        this.client = client;
        this.classifier = classifier;
        this.restrictionExtractor = restrictionExtractor;
        this.ingestionService = ingestionService;
    }

    /** Convenience constructor for connector tests without a custom restriction extractor. */
    public FederalRegisterIngestionService(FederalRegisterClient client, VisaTypeClassifier classifier,
            PolicyIngestionService ingestionService) {
        this(client, classifier, new EntryRestrictionExtractor(), ingestionService);
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
        // Only a presidential document can be an entry proclamation. Rules and notices often cite
        // one, such as "Presidential Proclamation 10949, Restricting the Entry of Foreign Nationals",
        // without restricting entry, and would otherwise be held for review with no alert sent.
        var restrictions = PRESIDENTIAL_DOCUMENT.equalsIgnoreCase(document.type())
                ? restrictionExtractor.extract(document.title(), content, document.htmlUrl())
                : NO_RESTRICTIONS;
        if (classification.visaTypes().isEmpty() && !restrictions.proclamation()) {
            throw new UnsupportedVisaCategoryException(
                    "No supported visa category was detected; document was not ingested");
        }
        return new PreparedDocument(document, content, classification, agencyNames(document), restrictions);
    }

    @Override
    public FederalRegisterIngestionResponse persistDeduplicated(PreparedDocument prepared,
            FederalRegisterImportRequest request) {
        FederalRegisterDocument document = prepared.document();
        String content = prepared.content();
        VisaClassification classification = prepared.classification();
        var restrictions = prepared.restrictions();
        String visaType = classification.visaTypes().isEmpty() ? "ENTRY_RESTRICTION"
                : String.join(",", classification.visaTypes());
        IngestionResponse ingestion = ingestionService.ingest(new IngestPolicyRequest(
                document.documentNumber(),
                document.title(),
                prepared.agency(),
                visaType,
                mapStatus(document.type(), document.action()),
                source().type(),
                document.publicationDate(),
                document.effectiveDate(),
                document.htmlUrl(),
                content, new java.util.LinkedHashSet<>(restrictions.affectedCountries()),
                new java.util.LinkedHashSet<>(restrictions.categories()), restrictions.sourceReferences(),
                restrictions.restrictions(), restrictions.requiresReview(), restrictions.specialDocumentHolderRule()),
                content,
                RuleIdentifiers.fromFederalRegisterApi(document.docketIds(), document.regulationIdNumbers()));

        // The shared ingestion transaction queues all matching profiles after commit.
        // Keep the response field for compatibility, without a synchronous AI call.
        return new FederalRegisterIngestionResponse(
                document.documentNumber(), document.title(), document.type(), prepared.agency(),
                document.publicationDate(), document.effectiveDate(), document.htmlUrl(),
                classification.affectsF1(), classification.affectsJ1(),
                classification.affectsH1b(), classification.visaTypes(),
                classification.matchedSignals(), ingestion, null);
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
            VisaClassification classification, String agency,
            EntryRestrictionExtractor.Extraction restrictions) {}

    private String agencyNames(FederalRegisterDocument document) {
        if (document.agencies() == null || document.agencies().isEmpty()) {
            return "Unknown Federal Agency";
        }
        return document.agencies().stream().map(FederalRegisterDocument.Agency::name)
                .distinct().collect(Collectors.joining("; "));
    }

    /**
     * A rule or proposed rule whose action withdraws it, such as "Proposed rule; withdrawal.", is
     * WITHDRAWN. Otherwise a Rule (final, interim final, a delay or a correction) is FINAL and a
     * Proposed Rule is PROPOSED; notices and anything else are UNKNOWN.
     */
    static PolicyStatus mapStatus(String type, String action) {
        boolean rule = "Rule".equalsIgnoreCase(type);
        boolean proposed = "Proposed Rule".equalsIgnoreCase(type);
        if ((rule || proposed) && action != null && action.toLowerCase(Locale.ROOT).contains("withdraw")) {
            return PolicyStatus.WITHDRAWN;
        }
        if (rule) {
            return PolicyStatus.FINAL;
        }
        return proposed ? PolicyStatus.PROPOSED : PolicyStatus.UNKNOWN;
    }
}
