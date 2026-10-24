package mygrant.ingestion;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

/** Stores complete policy records and their searchable, visa-tagged vector chunks. */
@Service
public class PolicyIngestionService {

    private final PolicyDocumentRepository policyRepository;
    private final VectorStore vectorStore;
    private final ApplicationEventPublisher eventPublisher;
    private final TokenTextSplitter textSplitter;

    public PolicyIngestionService(PolicyDocumentRepository policyRepository, VectorStore vectorStore,
            ApplicationEventPublisher eventPublisher) {
        this.policyRepository = policyRepository;
        this.vectorStore = vectorStore;
        this.eventPublisher = eventPublisher;
        this.textSplitter = new TokenTextSplitter();
    }

    /** Stores and indexes all content supplied by a manual ingestion request. */
    @Transactional
    public IngestionResponse ingest(IngestPolicyRequest request) {
        return ingest(request, request.content());
    }

    /** Stores the full policy while indexing a selected excerpt for retrieval. */
    @Transactional
    public IngestionResponse ingest(IngestPolicyRequest request, String indexedContent) {
        if (indexedContent == null || indexedContent.isBlank()) {
            throw new IllegalArgumentException("Indexed policy content must not be blank");
        }
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
                request.sourceUrl(), request.content(), contentHash);

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

        eventPublisher.publishEvent(new PolicyIndexedEvent(saved.getId(), saved.getTitle(),
                Arrays.asList(saved.getVisaType().split(",")), saved.getEffectiveDate(), saved.getContent()));

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

}
