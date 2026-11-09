package mygrant.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import mygrant.ingestion.dto.IngestionSyncResponse;
import mygrant.ingestion.federalregister.FederalRegisterClient;
import mygrant.ingestion.federalregister.FederalRegisterDocument;
import mygrant.policies.PolicyDocumentRepository;
import mygrant.sources.PolicySource;
import mygrant.sources.PolicySourceRepository;
import mygrant.sources.PolicySourceSyncRun;
import mygrant.sources.PolicySourceSyncRunRepository;
import mygrant.sources.PolicySourceType;
import mygrant.sources.SyncFailure;
import mygrant.sources.SyncFailureRepository;

/**
 * One bad document no longer stalls the Federal Register sync: it is recorded and retried on later
 * runs, up to a cap, while the rest are imported and the run completes. Real ingestion against
 * PostgreSQL, with the Federal Register API stubbed. Every test rolls back, so the schedulers of
 * other test contexts never see the source it creates.
 */
@SpringBootTest
@Transactional
class SourceSyncFailureIntegrationTest {

    private static final Principal ADMIN = () -> "admin@example.com";
    private static final String FIRST = "2099-90001";
    private static final String BAD = "2099-90002";
    private static final String THIRD = "2099-90003";
    private static final String OFF_TOPIC = "2099-90004";
    private static final String MALFORMED = "Malformed document " + BAD;

    @Autowired SourceSyncService sync;
    @Autowired PolicySourceRepository sources;
    @Autowired PolicySourceSyncRunRepository runs;
    @Autowired SyncFailureRepository failures;
    @Autowired PolicyDocumentRepository policies;
    @MockBean FederalRegisterClient client;
    @MockBean VectorStore vectors;

    private PolicySource source;

    @BeforeEach
    void createSourceAndStubDocuments() {
        source = sources.save(new PolicySource("Federal Register (sync failure test)",
                PolicySourceType.FEDERAL_REGISTER, "https://www.federalregister.gov", 3600));
        stubDocument(FIRST, "Practical training for F-1 students");
        stubDocument(THIRD, "Exchange visitor program sponsors and J-1 students");
        when(client.getDocument(BAD)).thenThrow(new IllegalStateException(MALFORMED));
    }

    @Test
    void theSecondOfThreeDocumentsFailingIsRecordedAndTheOthersAreImported() {
        when(client.searchDocumentNumbers(any(), any(), anyInt())).thenReturn(List.of(FIRST, BAD, THIRD));

        IngestionSyncResponse response = sync.syncOne(source.getId(), ADMIN);

        assertThat(response.succeeded()).isTrue();
        assertThat(response.documentsFetched()).isEqualTo(3);
        assertThat(response.error()).isEqualTo("1 of 3 documents failed: " + BAD
                + " (attempt 1 of 5, will be retried on the next run): " + MALFORMED);
        assertThat(policies.findByExternalId(FIRST)).isPresent();
        assertThat(policies.findByExternalId(THIRD)).isPresent();
        assertThat(policies.findByExternalId(BAD)).isEmpty();

        SyncFailure failure = failure(BAD);
        assertThat(failure.getStatus()).isEqualTo(SyncFailure.Status.RETRYING);
        assertThat(failure.getAttempts()).isEqualTo(1);
        assertThat(failure.getLastError()).isEqualTo(MALFORMED);
        assertThat(failure.getFirstFailedAt()).isNotNull().isEqualTo(failure.getLastFailedAt());
        assertThat(failure.getResolvedAt()).isNull();
        assertThat(failures.findBySource_IdAndDocumentIdIn(source.getId(), List.of(FIRST, THIRD))).isEmpty();

        // The run completed, so the search window moves on; the failure table brings BAD back.
        assertThat(source.getLastSuccessAt()).isNotNull();
        assertThat(source.getLastError()).isNull();
        PolicySourceSyncRun run = runs.findTopBySource_IdOrderByStartedAtDesc(source.getId()).orElseThrow();
        assertThat(run.isSucceeded()).isTrue();
        assertThat(run.getDocumentsFetched()).isEqualTo(3);
        assertThat(run.getError()).isEqualTo(response.error());

        // Admins see it.
        assertThat(sync.failures(SyncFailure.Status.RETRYING, org.springframework.data.domain.Pageable.unpaged())
                .getContent()).anySatisfy(seen -> {
                    assertThat(seen.documentId()).isEqualTo(BAD);
                    assertThat(seen.sourceId()).isEqualTo(source.getId());
                    assertThat(seen.sourceName()).isEqualTo(source.getName());
                    assertThat(seen.attempts()).isEqualTo(1);
                    assertThat(seen.maxAttempts()).isEqualTo(5);
                    assertThat(seen.lastError()).isEqualTo(MALFORMED);
                });
    }

    @Test
    void aDocumentThatKeepsFailingIsSkippedAfterFiveAttempts() {
        // Found once; after that only the failure table brings it back.
        when(client.searchDocumentNumbers(any(), any(), anyInt())).thenReturn(List.of(BAD), List.of());

        for (int attempt = 1; attempt <= 4; attempt++) {
            IngestionSyncResponse response = sync.syncOne(source.getId(), ADMIN);
            assertThat(response.succeeded()).isTrue();
            assertThat(response.error()).contains("(attempt " + attempt + " of 5, will be retried on the next run)");
            assertThat(failure(BAD).getStatus()).isEqualTo(SyncFailure.Status.RETRYING);
        }
        IngestionSyncResponse fifth = sync.syncOne(source.getId(), ADMIN);
        assertThat(fifth.succeeded()).isTrue();
        assertThat(fifth.error()).isEqualTo("1 of 1 documents failed: " + BAD
                + " (attempt 5 of 5, will not be retried): " + MALFORMED);
        assertThat(failure(BAD).getStatus()).isEqualTo(SyncFailure.Status.PERMANENTLY_FAILED);
        assertThat(failure(BAD).getAttempts()).isEqualTo(5);

        // Later runs skip it, even when the search finds it again.
        when(client.searchDocumentNumbers(any(), any(), anyInt())).thenReturn(List.of(BAD));
        IngestionSyncResponse later = sync.syncOne(source.getId(), ADMIN);

        assertThat(later.succeeded()).isTrue();
        assertThat(later.documentsFetched()).isZero();
        assertThat(later.error()).isNull();
        verify(client, times(5)).getDocument(BAD);
        assertThat(failure(BAD).getAttempts()).isEqualTo(5);
        assertThat(failure(BAD).getStatus()).isEqualTo(SyncFailure.Status.PERMANENTLY_FAILED);
    }

    @Test
    void aFailedDocumentIsImportedByALaterRunOnceItIsFixed() {
        when(client.searchDocumentNumbers(any(), any(), anyInt())).thenReturn(List.of(FIRST, BAD, THIRD), List.of());
        assertThat(sync.syncOne(source.getId(), ADMIN).error()).contains(BAD);

        // The document is fixed upstream. The next search no longer finds it; the retry does.
        FederalRegisterDocument fixed = document(BAD, "Form I-20 changes for F-1 students");
        doReturn(fixed).when(client).getDocument(BAD);
        when(client.downloadFullText(fixed.rawTextUrl())).thenReturn("Schools will issue a new Form I-20.");

        IngestionSyncResponse retried = sync.syncOne(source.getId(), ADMIN);

        assertThat(retried.succeeded()).isTrue();
        assertThat(retried.documentsFetched()).isEqualTo(1);
        assertThat(retried.error()).isNull();
        assertThat(policies.findByExternalId(BAD)).isPresent();
        SyncFailure failure = failure(BAD);
        assertThat(failure.getStatus()).isEqualTo(SyncFailure.Status.RESOLVED);
        assertThat(failure.getResolvedAt()).isNotNull();
        assertThat(failure.getAttempts()).isEqualTo(1);

        // Once resolved it is not retried again.
        assertThat(sync.syncOne(source.getId(), ADMIN).documentsFetched()).isZero();
        verify(client, times(2)).getDocument(BAD);
    }

    @Test
    void aCandidateAboutNoSupportedVisaTypeIsNotAFailure() {
        stubDocument(OFF_TOPIC, "Pipeline safety: hazardous liquid transportation");
        when(client.searchDocumentNumbers(any(), any(), anyInt())).thenReturn(List.of(OFF_TOPIC));

        IngestionSyncResponse response = sync.syncOne(source.getId(), ADMIN);

        assertThat(response.succeeded()).isTrue();
        assertThat(response.documentsFetched()).isEqualTo(1);
        assertThat(response.error()).isNull();
        assertThat(policies.findByExternalId(OFF_TOPIC)).isEmpty();
        assertThat(failures.findBySource_IdAndDocumentIdIn(source.getId(), List.of(OFF_TOPIC))).isEmpty();
    }

    @Test
    void aFailedSearchStillFailsTheRunAndKeepsTheWindow() {
        when(client.searchDocumentNumbers(any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("Federal Register search unavailable"));

        IngestionSyncResponse response = sync.syncOne(source.getId(), ADMIN);

        assertThat(response.succeeded()).isFalse();
        assertThat(response.error()).isEqualTo("Federal Register search unavailable");
        assertThat(source.getLastSuccessAt()).isNull();
        assertThat(source.getLastError()).isEqualTo("Federal Register search unavailable");
    }

    private SyncFailure failure(String documentNumber) {
        return failures.findBySource_IdAndDocumentIdIn(source.getId(), List.of(documentNumber)).stream()
                .findFirst().orElseThrow();
    }

    private void stubDocument(String number, String title) {
        FederalRegisterDocument document = document(number, title);
        when(client.getDocument(number)).thenReturn(document);
        when(client.downloadFullText(document.rawTextUrl())).thenReturn(title + ". Synthetic text for " + number + ".");
    }

    private static FederalRegisterDocument document(String number, String title) {
        return new FederalRegisterDocument(number, title, "Rule", LocalDate.of(2026, 11, 2), null,
                "https://www.federalregister.gov/d/" + number, "https://example.test/" + number + ".txt",
                List.of(new FederalRegisterDocument.Agency("Homeland Security Department",
                        "homeland-security-department")));
    }
}
