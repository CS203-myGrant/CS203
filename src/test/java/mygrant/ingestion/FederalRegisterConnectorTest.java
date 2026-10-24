package mygrant.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import mygrant.impact.ProfileImpactAssessmentService;
import mygrant.ingestion.classification.VisaClassification;
import mygrant.ingestion.classification.VisaTypeClassifier;
import mygrant.ingestion.connector.IngestionConnector;
import mygrant.ingestion.connector.ConnectorHealth;
import mygrant.ingestion.dto.IngestPolicyRequest;
import mygrant.ingestion.dto.IngestionResponse;
import mygrant.ingestion.federalregister.FederalRegisterClient;
import mygrant.ingestion.federalregister.FederalRegisterDocument;
import mygrant.ingestion.federalregister.FederalRegisterImportRequest;
import mygrant.ingestion.federalregister.FederalRegisterIngestionResponse;
import mygrant.ingestion.federalregister.FederalRegisterIngestionService;
import mygrant.policies.SourceType;

@ExtendWith(MockitoExtension.class)
class FederalRegisterConnectorTest {
    @Mock private FederalRegisterClient client;
    @Mock private VisaTypeClassifier classifier;
    @Mock private PolicyIngestionService policyIngestion;
    @Mock private ProfileImpactAssessmentService impactAssessment;

    @Test
    void connectorKeepsFederalRegisterImportAndDuplicateResult() {
        var document = new FederalRegisterDocument("2026-12345", "F-1 student rule", "Rule",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1),
                "https://www.federalregister.gov/d/2026-12345", "https://example.com/raw.txt",
                List.of(new FederalRegisterDocument.Agency("Department of Homeland Security", "dhs")));
        when(client.getDocument(document.documentNumber())).thenReturn(document);
        when(client.downloadFullText(document.rawTextUrl())).thenReturn("F-1 student policy");
        when(classifier.classify(document.title(), "F-1 student policy"))
                .thenReturn(new VisaClassification(true, false, false, List.of("F-1"), List.of("F-1")));
        when(policyIngestion.ingest(any(IngestPolicyRequest.class), eq("F-1 student policy")))
                .thenReturn(new IngestionResponse(42L, document.documentNumber(), 0, Instant.now(), true));

        IngestionConnector<FederalRegisterImportRequest,
                FederalRegisterIngestionService.FetchedDocument,
                FederalRegisterIngestionService.PreparedDocument,
                FederalRegisterIngestionResponse> connector = service();
        var result = connector.ingest(new FederalRegisterImportRequest(document.documentNumber(),
                "admin@example.com"));

        assertThat(connector.source().id()).isEqualTo("FEDERAL_REGISTER");
        assertThat(connector.source().type()).isEqualTo(SourceType.PRIMARY);
        assertThat(connector.health().status()).isEqualTo(ConnectorHealth.Status.OK);
        assertThat(connector.health().lastAttemptAt()).isNotNull();
        assertThat(connector.health().lastSuccessAt()).isNotNull();
        assertThat(result.ingestion().alreadyIndexed()).isTrue();
        assertThat(result.detectedVisaTypes()).containsExactly("F-1");
        var request = ArgumentCaptor.forClass(IngestPolicyRequest.class);
        verify(policyIngestion).ingest(request.capture(), eq("F-1 student policy"));
        assertThat(request.getValue().sourceType()).isEqualTo(SourceType.PRIMARY);
        assertThat(request.getValue().externalId()).isEqualTo(document.documentNumber());
        verify(impactAssessment).assess(eq("admin@example.com"), eq(document),
                eq("Department of Homeland Security"), eq("F-1 student policy"), any());
    }

    @Test
    void unsupportedVisaIsRejectedBeforePersistence() {
        var document = new FederalRegisterDocument("2026-12345", "Unrelated rule", "Rule",
                LocalDate.now(), null, "https://example.com/html", "https://example.com/raw", List.of());
        when(client.getDocument(document.documentNumber())).thenReturn(document);
        when(client.downloadFullText(document.rawTextUrl())).thenReturn("Unrelated text");
        when(classifier.classify(document.title(), "Unrelated text"))
                .thenReturn(new VisaClassification(false, false, false, List.of(), List.of()));

        FederalRegisterIngestionService connector = service();
        assertThatThrownBy(() -> connector.ingest(new FederalRegisterImportRequest(
                document.documentNumber(), "admin@example.com")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(connector.health().status()).isEqualTo(ConnectorHealth.Status.FAILED);
        assertThat(connector.health().errorStage()).isEqualTo(ConnectorHealth.Stage.TRANSFORM);
        assertThat(connector.health().errorMessage()).contains("No supported visa category");
        verify(policyIngestion, never()).ingest(any(), any());
    }

    @Test
    void fetchFailureReportsSourceStageAndDoesNotPersist() {
        when(client.getDocument("2026-12345")).thenThrow(new IllegalStateException("Upstream unavailable"));
        var connector = service();

        assertThatThrownBy(() -> connector.ingest(new FederalRegisterImportRequest(
                "2026-12345", "admin@example.com")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(connector.health().source().id()).isEqualTo("FEDERAL_REGISTER");
        assertThat(connector.health().status()).isEqualTo(ConnectorHealth.Status.FAILED);
        assertThat(connector.health().lastAttemptAt()).isNotNull();
        assertThat(connector.health().errorStage()).isEqualTo(ConnectorHealth.Stage.FETCH);
        assertThat(connector.health().errorMessage()).isEqualTo("Upstream unavailable");
        verify(policyIngestion, never()).ingest(any(), any());
    }

    @Test
    void savedFederalRegisterResponseKeepsFalsePositiveOutOfClassification() throws Exception {
        String content;
        try (var stream = getClass().getResourceAsStream("/federal-register/2026-18631.txt")) {
            content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var document = new FederalRegisterDocument("2026-18631",
                "Eliminating the Discretionary 60-Day Grace Period", "Rule",
                LocalDate.of(2026, 9, 1), null, "https://www.federalregister.gov/d/2026-18631",
                "https://example.com/2026-18631.txt", List.of());
        when(client.getDocument(document.documentNumber())).thenReturn(document);
        when(client.downloadFullText(document.rawTextUrl())).thenReturn(content);

        var connector = new FederalRegisterIngestionService(client, new VisaTypeClassifier(),
                policyIngestion, impactAssessment);
        var request = new FederalRegisterImportRequest(document.documentNumber(), "admin@example.com");
        var fetched = connector.fetch(request);
        var prepared = connector.transform(fetched, request);

        assertThat(fetched.sourceUrl()).isEqualTo(document.htmlUrl());
        assertThat(fetched.fetchedAt()).isNotNull();
        assertThat(prepared.classification().visaTypes()).containsExactly("H-1B");
        verify(policyIngestion, never()).ingest(any(), any());
    }

    private FederalRegisterIngestionService service() {
        return new FederalRegisterIngestionService(client, classifier, policyIngestion, impactAssessment);
    }
}
