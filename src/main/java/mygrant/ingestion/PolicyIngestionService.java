package mygrant.ingestion;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mygrant.ingestion.dto.IngestPolicyRequest;
import mygrant.ingestion.dto.IngestionResponse;
import mygrant.ingestion.connector.ContentHash;
import mygrant.policies.PolicyDocument;
import mygrant.policies.PolicyDocumentRepository;
import mygrant.policies.PolicyIndexedEvent;
import mygrant.policies.SourceType;
import mygrant.policies.CountryIdentifiers;
import mygrant.rules.PolicyRuleTracker;
import mygrant.rules.RecordedBy;
import mygrant.rules.RuleIdentifier;

/** Stores complete policy records and their searchable, visa-tagged vector chunks. */
@Service
public class PolicyIngestionService {

    private final PolicyDocumentRepository policyRepository;
    private final VectorStore vectorStore;
    private final ApplicationEventPublisher eventPublisher;
    private final TokenTextSplitter textSplitter;
    private final PolicyRuleTracker ruleTracker;

    public PolicyIngestionService(PolicyDocumentRepository policyRepository, VectorStore vectorStore,
            ApplicationEventPublisher eventPublisher, PolicyRuleTracker ruleTracker) {
        this.policyRepository = policyRepository;
        this.vectorStore = vectorStore;
        this.eventPublisher = eventPublisher;
        this.textSplitter = new TokenTextSplitter();
        this.ruleTracker = ruleTracker;
    }

    /** Stores and indexes all content supplied by a manual ingestion request. */
    @Transactional
    public IngestionResponse ingest(IngestPolicyRequest request) {
        return ingest(request, request.content());
    }

    /** Stores the full policy while indexing a selected excerpt for retrieval. */
    @Transactional
    public IngestionResponse ingest(IngestPolicyRequest request, String indexedContent) {
        return ingest(request, indexedContent, Set.of());
    }

    /**
     * The same, with dockets, RINs or linked document numbers the source supplied, such as the
     * Federal Register API's. They are added to those read from the document's own header when
     * the document is put in its policy rule (CS203-86).
     */
    @Transactional
    public IngestionResponse ingest(IngestPolicyRequest request, String indexedContent,
            Set<RuleIdentifier> ruleIdentifiers) {
        if (indexedContent == null || indexedContent.isBlank()) {
            throw new IllegalArgumentException("Indexed policy content must not be blank");
        }
        var affectedCountries = request.affectedCountries() == null ? List.<String>of()
                : request.affectedCountries().stream().map(CountryIdentifiers::normalize).distinct().toList();
        var restrictionCategories = request.restrictionCategories() == null
                ? java.util.Set.<mygrant.policies.RestrictionCategory>of()
                : java.util.Set.copyOf(request.restrictionCategories());
        var sourceReferences = request.sourceReferences() == null ? List.<String>of()
                : request.sourceReferences().stream().map(PolicyIngestionService::validateSourceReference)
                        .distinct().toList();
        String contentHash = ContentHash.sha256(request.content());
        var existing = policyRepository.findByExternalId(request.externalId());

        if (existing.isPresent()) {
            PolicyDocument document = existing.get();
            if (document.getContentHash().equals(contentHash) && document.getIndexedAt() != null) {
                return new IngestionResponse(document.getId(), document.getExternalId(), 0,
                        document.getIndexedAt(), true);
            }
            throw new PolicyAlreadyExistsException(request.externalId());
        }

        PolicyDocument policy = new PolicyDocument(
                request.externalId(), request.title(), request.agency(), normalizeVisaType(request.visaType()),
                request.status(), request.sourceType(), request.publicationDate(), request.effectiveDate(),
                request.sourceUrl(), request.content(), contentHash, new java.util.LinkedHashSet<>(affectedCountries),
                restrictionCategories, sourceReferences);

        PolicyDocument saved = policyRepository.save(policy);
        List<Document> sources = Arrays.stream(saved.getVisaType().split(","))
                .map(String::trim)
                .map(visaType -> new Document(saved.getExternalId() + ":" + visaType,
                        indexedContent, createMetadata(saved, visaType)))
                .toList();
        List<Document> chunks = textSplitter.apply(sources);

        vectorStore.add(chunks);

        Instant indexedAt = Instant.now();
        saved.markIndexed(indexedAt);
        policyRepository.save(saved);
        // In this transaction, so a document is never stored without its rule. Last, so the rule
        // lock is held only until commit, not through the embedding call above. The row is already
        // inserted: IDENTITY ids make Hibernate insert on the first save.
        ruleTracker.track(saved, ruleIdentifiers, RecordedBy.INGESTION);

        // Profile impact alerts describe official policy changes. News is searchable
        // as secondary evidence but must not create a policy-change notification.
        if (saved.getSourceType() == SourceType.PRIMARY) {
            eventPublisher.publishEvent(PolicyIndexedEvent.from(saved));
        }

        return new IngestionResponse(saved.getId(), saved.getExternalId(), chunks.size(), indexedAt, false);
    }

    private Map<String, Object> createMetadata(PolicyDocument policy, String visaType) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("policyId", policy.getId().toString());
        metadata.put("externalId", policy.getExternalId());
        metadata.put("title", policy.getTitle());
        metadata.put("agency", policy.getAgency());
        metadata.put("visaType", visaType);
        metadata.put("status", policy.getStatus().name());
        metadata.put("sourceType", policy.getSourceType().name());
        metadata.put("publicationDate", policy.getPublicationDate().toString());
        metadata.put("sourceUrl", policy.getSourceUrl());
        metadata.put("affectedCountries", policy.getAffectedCountries());
        metadata.put("restrictionCategories", policy.getRestrictionCategories().stream()
                .map(Enum::name).toList());
        metadata.put("sourceReferences", policy.getSourceReferences());
        if (policy.getEffectiveDate() != null) {
            metadata.put("effectiveDate", policy.getEffectiveDate().toString());
        }
        return metadata;
    }

    private String normalizeVisaType(String visaType) {
        return Arrays.stream(visaType.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .distinct()
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String validateSourceReference(String reference) {
        try {
            var uri = java.net.URI.create(reference.trim());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("Source references must be absolute HTTP(S) URLs");
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid source reference URL", exception);
        }
    }

}
