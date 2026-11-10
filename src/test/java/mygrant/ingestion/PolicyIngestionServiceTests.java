package mygrant.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.ArrayList;

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
import mygrant.policies.RestrictionCategory;
import mygrant.rules.DocumentOrigin;
import mygrant.rules.PolicyRuleTracker;
import mygrant.rules.RecordedBy;
import mygrant.rules.RuleIdentifier;

/** Verifies policy storage, selected-excerpt indexing, and idempotent re-imports. */
@ExtendWith(MockitoExtension.class)
class PolicyIngestionServiceTests {

    @Mock
    private PolicyDocumentRepository repository;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private PolicyRuleTracker ruleTracker;

    @org.junit.jupiter.api.BeforeEach
    void noRuleChange() {
        org.mockito.Mockito.lenient().when(ruleTracker.track(any(), any(), any(), any()))
                .thenReturn(new PolicyRuleTracker.Result(7L, true, false));
    }

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

        PolicyIngestionService service = new PolicyIngestionService(repository, vectorStore, eventPublisher, ruleTracker);
        var response = service.ingest(request);

        assertThat(response.policyId()).isEqualTo(1L);
        assertThat(response.externalId()).isEqualTo("2026-14439");
        assertThat(response.chunksIndexed()).isPositive();
        assertThat(response.alreadyIndexed()).isFalse();
        verify(vectorStore).add(any());
        verify(eventPublisher).publishEvent(new mygrant.impact.PolicyAssessmentRequested(1L));
        verify(eventPublisher).publishEvent(argThat((Object event) -> event instanceof PolicyIndexedEvent indexed
                && indexed.policyId().equals(1L) && indexed.visaTypes().equals(java.util.List.of("F-1"))));
        verify(ruleTracker).track(argThat(policy -> policy.getId().equals(1L)), eq(java.util.Set.of()),
                eq(RecordedBy.INGESTION), eq(DocumentOrigin.AUTOMATIC));
    }

    @Test
    void aPassageAfterSixThousandCharactersIsInSearchableChunks() {
        IngestPolicyRequest base = request();
        String tail = "Unique exemption for students after the long preamble.";
        String content = "Federal Register policy introduction. ".repeat(240) + tail;
        IngestPolicyRequest request = new IngestPolicyRequest(base.externalId(), base.title(),
                base.agency(), base.visaType(), base.status(), base.sourceType(),
                base.publicationDate(), base.effectiveDate(), base.sourceUrl(), content);
        when(repository.findByExternalId(request.externalId())).thenReturn(Optional.empty());
        when(repository.save(any(PolicyDocument.class))).thenAnswer(invocation -> {
            PolicyDocument policy = invocation.getArgument(0);
            ReflectionTestUtils.setField(policy, "id", 1L);
            return policy;
        });
        var indexed = new ArrayList<org.springframework.ai.document.Document>();
        org.mockito.Mockito.doAnswer(invocation -> {
            indexed.addAll(invocation.getArgument(0));
            return null;
        }).when(vectorStore).add(any());

        new PolicyIngestionService(repository, vectorStore, eventPublisher, ruleTracker).ingest(request);

        assertThat(content.indexOf(tail)).isGreaterThan(6_000);
        assertThat(indexed).anySatisfy(chunk -> {
            assertThat(chunk.getText()).contains(tail);
            assertThat(chunk.getMetadata()).containsEntry("externalId", base.externalId());
        });
    }

    @Test
    void aPolicyAnAdminPostedReachesItsRuleMarkedAsTheAdmins() {
        IngestPolicyRequest request = request();
        when(repository.findByExternalId(request.externalId())).thenReturn(Optional.empty());
        when(repository.save(any(PolicyDocument.class))).thenAnswer(invocation -> {
            PolicyDocument policy = invocation.getArgument(0);
            if (policy.getId() == null) {
                ReflectionTestUtils.setField(policy, "id", 1L);
            }
            return policy;
        });

        new PolicyIngestionService(repository, vectorStore, eventPublisher, ruleTracker).ingestRecordedByAdmin(request);

        verify(ruleTracker).track(argThat(policy -> policy.getId().equals(1L)), eq(java.util.Set.of()),
                eq(RecordedBy.INGESTION), eq(DocumentOrigin.ADMIN_RECORDED));
    }

    @Test
    void normalizesAndPersistsPolicyCountryAndSourceMetadata() {
        IngestPolicyRequest base = request();
        IngestPolicyRequest request = new IngestPolicyRequest(
                base.externalId(), base.title(), base.agency(), base.visaType(), base.status(),
                base.sourceType(), base.publicationDate(), base.effectiveDate(), base.sourceUrl(), base.content(),
                java.util.Set.of("my", "US"), java.util.Set.of(RestrictionCategory.VISA_ISSUANCE),
                java.util.List.of(" https://example.gov/rule "));
        when(repository.findByExternalId(request.externalId())).thenReturn(Optional.empty());
        when(repository.save(any(PolicyDocument.class))).thenAnswer(invocation -> {
            PolicyDocument policy = invocation.getArgument(0);
            if (policy.getId() == null) {
                ReflectionTestUtils.setField(policy, "id", 1L);
            }
            return policy;
        });

        new PolicyIngestionService(repository, vectorStore, eventPublisher, ruleTracker).ingest(request);

        verify(repository, atLeastOnce()).save(argThat(policy -> policy.getAffectedCountries().equals(java.util.Set.of("MY", "US"))
                && policy.getRestrictionCategories().equals(java.util.Set.of(RestrictionCategory.VISA_ISSUANCE))
                && policy.getSourceReferences().equals(java.util.List.of("https://example.gov/rule"))));
        verify(vectorStore).add(argThat(chunks -> chunks.stream().allMatch(chunk ->
                chunk.getMetadata().get("affectedCountries").equals(java.util.Set.of("MY", "US"))
                && chunk.getMetadata().get("restrictionCategories")
                        .equals(java.util.List.of("VISA_ISSUANCE"))
                && chunk.getMetadata().get("sourceReferences")
                        .equals(java.util.List.of("https://example.gov/rule")))));
    }

    @Test
    void rejectsUnknownCountryIdentifiersBeforeSavingPolicy() {
        IngestPolicyRequest base = request();
        IngestPolicyRequest request = new IngestPolicyRequest(
                base.externalId(), base.title(), base.agency(), base.visaType(), base.status(),
                base.sourceType(), base.publicationDate(), base.effectiveDate(), base.sourceUrl(), base.content(),
                java.util.Set.of("Malaysia"), java.util.Set.of(), java.util.List.of());

        assertThatThrownBy(() -> new PolicyIngestionService(repository, vectorStore, eventPublisher, ruleTracker).ingest(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO 3166-1 alpha-2");
        verify(repository, never()).save(any(PolicyDocument.class));
    }

    @Test
    void rejectsNonHttpPolicySourceReferencesBeforeSavingPolicy() {
        IngestPolicyRequest base = request();
        IngestPolicyRequest request = new IngestPolicyRequest(
                base.externalId(), base.title(), base.agency(), base.visaType(), base.status(),
                base.sourceType(), base.publicationDate(), base.effectiveDate(), base.sourceUrl(), base.content(),
                java.util.Set.of(), java.util.Set.of(), java.util.List.of("javascript:alert(1)"));

        assertThatThrownBy(() -> new PolicyIngestionService(repository, vectorStore, eventPublisher, ruleTracker).ingest(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid source reference URL");
        verify(repository, never()).save(any(PolicyDocument.class));
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

        PolicyIngestionService service = new PolicyIngestionService(repository, vectorStore, eventPublisher, ruleTracker);
        var response = service.ingest(request);

        assertThat(response.alreadyIndexed()).isTrue();
        assertThat(response.chunksIndexed()).isZero();
        verify(vectorStore, never()).add(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
        // A repeat import must not touch the rule, or it could add history twice.
        verify(ruleTracker, never()).track(any(), any(), any(), any());
    }

    @Test
    void passesSourceIdentifiersToTheRuleTracker() {
        IngestPolicyRequest request = request();
        when(repository.findByExternalId(request.externalId())).thenReturn(Optional.empty());
        when(repository.save(any(PolicyDocument.class))).thenAnswer(invocation -> {
            PolicyDocument policy = invocation.getArgument(0);
            if (policy.getId() == null) {
                ReflectionTestUtils.setField(policy, "id", 1L);
            }
            return policy;
        });
        var identifiers = java.util.Set.of(RuleIdentifier.docket("ICEB-2025-0001"), RuleIdentifier.rin("1653-AA95"));

        new PolicyIngestionService(repository, vectorStore, eventPublisher, ruleTracker)
                .ingest(request, "Selected summary only.", identifiers);

        verify(ruleTracker).track(argThat(policy -> policy.getId().equals(1L)), same(identifiers),
                eq(RecordedBy.INGESTION), eq(DocumentOrigin.AUTOMATIC));
    }

    @Test
    void aPolicyThatMovedItsRuleIsMarkedSoItGetsOnlyTheChangeAlert() {
        IngestPolicyRequest request = request();
        when(repository.findByExternalId(request.externalId())).thenReturn(Optional.empty());
        when(repository.save(any(PolicyDocument.class))).thenAnswer(invocation -> {
            PolicyDocument policy = invocation.getArgument(0);
            if (policy.getId() == null) {
                ReflectionTestUtils.setField(policy, "id", 1L);
            }
            return policy;
        });
        when(ruleTracker.track(any(), any(), any(), any())).thenReturn(new PolicyRuleTracker.Result(7L, true, true));

        new PolicyIngestionService(repository, vectorStore, eventPublisher, ruleTracker).ingest(request);

        verify(eventPublisher).publishEvent(argThat((Object event) -> event instanceof PolicyIndexedEvent indexed
                && indexed.ruleChanged()));
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

        PolicyIngestionService service = new PolicyIngestionService(repository, vectorStore, eventPublisher, ruleTracker);
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

        new PolicyIngestionService(repository, vectorStore, eventPublisher, ruleTracker).ingest(secondary);

        verify(vectorStore).add(argThat(chunks -> chunks.stream()
                .allMatch(chunk -> "SECONDARY".equals(chunk.getMetadata().get("sourceType")))));
        verify(eventPublisher, never()).publishEvent(any(PolicyIndexedEvent.class));
        verify(eventPublisher).publishEvent(new mygrant.impact.PolicyAssessmentRequested(2L));
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
