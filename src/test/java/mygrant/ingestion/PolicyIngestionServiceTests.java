package mygrant.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import mygrant.ingestion.dto.IngestPolicyRequest;
import mygrant.policies.PolicyDocument;
import mygrant.policies.PolicyDocumentRepository;
import mygrant.policies.PolicyIndexedEvent;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;

/** Verifies policy storage, selected-excerpt indexing, and idempotent re-imports. */
@ExtendWith(MockitoExtension.class)
class PolicyIngestionServiceTests {

    @Mock
    private PolicyDocumentRepository repository;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Test
    void indexesANewPolicyDocument() {
        IngestPolicyRequest request = request();
        when(repository.findByExternalId(request.externalId())).thenReturn(Optional.empty());
        when(repository.save(any(PolicyDocument.class))).thenAnswer(invocation -> {
            PolicyDocument policy = invocation.getArgument(0);
            if (policy.getId() == null) {
                ReflectionTestUtils.setField(policy, "id", 1L);
            }
            return policy;
        });

        PolicyIngestionService service = new PolicyIngestionService(repository, vectorStore, eventPublisher);
        var response = service.ingest(request);

        assertThat(response.policyId()).isEqualTo(1L);
        assertThat(response.externalId()).isEqualTo("2026-14439");
        assertThat(response.chunksIndexed()).isPositive();
        assertThat(response.alreadyIndexed()).isFalse();
        verify(vectorStore).add(any());
        verify(eventPublisher).publishEvent(argThat((Object event) -> event instanceof PolicyIndexedEvent indexed
                && indexed.policyId().equals(1L) && indexed.visaTypes().equals(java.util.List.of("F-1"))));
    }

    @Test
    void doesNotReindexIdenticalContent() {
        IngestPolicyRequest request = request();
        PolicyDocument existing = new PolicyDocument(
                request.externalId(), request.title(), request.agency(), request.visaType(),
                request.status(), request.sourceType(), request.publicationDate(), request.effectiveDate(),
                request.sourceUrl(), request.content(),
                "2ff56864fc3aedd98dca20508ca6f86472f5eb0137b33f845b2be5bafa6e28a9");
        ReflectionTestUtils.setField(existing, "id", 1L);
        existing.markIndexed(Instant.parse("2026-09-15T00:00:00Z"));
        when(repository.findByExternalId(request.externalId())).thenReturn(Optional.of(existing));

        PolicyIngestionService service = new PolicyIngestionService(repository, vectorStore, eventPublisher);
        var response = service.ingest(request);

        assertThat(response.alreadyIndexed()).isTrue();
        assertThat(response.chunksIndexed()).isZero();
        verify(vectorStore, never()).add(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void retainsFullPolicyButIndexesOnlyTheSelectedExcerpt() {
        IngestPolicyRequest request = request();
        when(repository.findByExternalId(request.externalId())).thenReturn(Optional.empty());
        when(repository.save(any(PolicyDocument.class))).thenAnswer(invocation -> {
            PolicyDocument policy = invocation.getArgument(0);
            if (policy.getId() == null) {
                ReflectionTestUtils.setField(policy, "id", 1L);
            }
            return policy;
        });

        PolicyIngestionService service = new PolicyIngestionService(repository, vectorStore, eventPublisher);
        service.ingest(request, "Selected summary only.");

        verify(repository, atLeastOnce()).save(argThat(policy -> policy.getContent().equals(request.content())));
        verify(vectorStore).add(argThat(chunks -> chunks.stream()
                .allMatch(chunk -> chunk.getText().contains("Selected summary only."))));
        // Impact rules read the whole policy, not just the excerpt kept for retrieval.
        verify(eventPublisher).publishEvent(argThat((Object event) -> event instanceof PolicyIndexedEvent indexed
                && indexed.content().equals(request.content())));
    }

    @Test
    void secondaryEvidenceIsIndexedWithoutOfficialPolicyAlert() {
        IngestPolicyRequest primary = request();
        IngestPolicyRequest secondary = new IngestPolicyRequest(
                "rss:example", "F-1 student visa report", "The Guardian", "F-1",
                mygrant.policies.PolicyStatus.UNKNOWN, SourceType.SECONDARY,
                primary.publicationDate(), null,
                "https://www.theguardian.com/us-news/example", "F-1 student visa report summary");
        when(repository.findByExternalId(secondary.externalId())).thenReturn(Optional.empty());
        when(repository.save(any(PolicyDocument.class))).thenAnswer(invocation -> {
            PolicyDocument policy = invocation.getArgument(0);
            if (policy.getId() == null) {
                ReflectionTestUtils.setField(policy, "id", 2L);
            }
            return policy;
        });

        new PolicyIngestionService(repository, vectorStore, eventPublisher).ingest(secondary);

        verify(vectorStore).add(argThat(chunks -> chunks.stream()
                .allMatch(chunk -> "SECONDARY".equals(chunk.getMetadata().get("sourceType")))));
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    private IngestPolicyRequest request() {
        return new IngestPolicyRequest(
                "2026-14439",
                "Fixed Time Period of Admission",
                "Department of Homeland Security",
                "F-1",
                PolicyStatus.FINAL,
                SourceType.PRIMARY,
                LocalDate.of(2026, 7, 17),
                LocalDate.of(2026, 9, 15),
                "https://www.federalregister.gov/example",
                "Recent policy text for the retrieval demonstration.");
    }
}
