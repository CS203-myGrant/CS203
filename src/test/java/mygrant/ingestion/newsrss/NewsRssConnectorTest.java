package mygrant.ingestion.newsrss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import mygrant.ingestion.PolicyIngestionService;
import mygrant.ingestion.classification.VisaTypeClassifier;
import mygrant.ingestion.connector.ConnectorHealth;
import mygrant.ingestion.connector.ConnectorStatusService;
import mygrant.ingestion.connector.SourceIdentity;
import mygrant.ingestion.dto.IngestPolicyRequest;
import mygrant.ingestion.dto.IngestionResponse;
import mygrant.policies.SourceType;

@ExtendWith(MockitoExtension.class)
class NewsRssConnectorTest {
    @Mock NewsRssClient client;
    @Mock PolicyIngestionService ingestion;
    NewsRssConnector connector;

    @BeforeEach
    void setUp() {
        connector = new NewsRssConnector(client, new NewsRssParser(),
                new VisaTypeClassifier(), ingestion);
    }

    @Test
    void importsOnlyRelevantArticlesWithSecondaryProvenanceAndDeduplicates() throws Exception {
        when(ingestion.ingest(any())).thenReturn(
                new IngestionResponse(1L, "rss:1", 1, Instant.now(), false),
                new IngestionResponse(2L, "rss:2", 1, Instant.now(), false),
                new IngestionResponse(1L, "rss:1", 0, Instant.now(), true),
                new IngestionResponse(2L, "rss:2", 0, Instant.now(), true));
        String sample = savedFeed();
        var first = connector.importSaved(sample);
        var second = connector.importSaved(sample);
        assertThat(first.imported()).isEqualTo(2);
        assertThat(first.unrelated()).isEqualTo(1);
        assertThat(first.invalidItems()).isEqualTo(1);
        assertThat(second.duplicates()).isEqualTo(2);
        assertThat(connector.health().status()).isEqualTo(ConnectorHealth.Status.OK);

        ArgumentCaptor<IngestPolicyRequest> captured = ArgumentCaptor.forClass(IngestPolicyRequest.class);
        verify(ingestion, org.mockito.Mockito.times(4)).ingest(captured.capture());
        assertThat(captured.getAllValues()).allSatisfy(request -> {
            assertThat(request.sourceType()).isEqualTo(SourceType.SECONDARY);
            assertThat(request.status().name()).isEqualTo("UNKNOWN");
            assertThat(request.sourceUrl()).startsWith("https://www.theguardian.com/");
            assertThat(request.publicationDate()).isNotNull();
            assertThat(request.externalId()).startsWith("rss:guardian:");
        });
        assertThat(captured.getAllValues().get(0).publicationDate())
                .isEqualTo(LocalDate.of(2026, 10, 21));
    }

    @Test
    void rateLimitAndTimeoutFailOnlyNewsHealthAndPreserveOtherConnectorViews() {
        ConnectorHealth other = ConnectorHealth.notChecked(
                new SourceIdentity("FEDERAL_REGISTER", SourceType.PRIMARY));
        ConnectorStatusService status = new ConnectorStatusService(List.of(connector,
                new FixedConnector(other)));
        when(client.download()).thenThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS))
                .thenThrow(new ResourceAccessException("timeout", new SocketTimeoutException()));

        assertThatThrownBy(connector::pull).isInstanceOf(HttpClientErrorException.class);
        assertThat(connector.health().errorStage()).isEqualTo(ConnectorHealth.Stage.FETCH);
        assertThat(status.status()).extracting(health -> health.source().id())
                .containsExactly("FEDERAL_REGISTER", "NEWS_RSS");
        assertThat(status.status().get(0).status()).isEqualTo(ConnectorHealth.Status.NOT_CHECKED);

        assertThatThrownBy(connector::pull).isInstanceOf(ResourceAccessException.class);
        assertThat(connector.health().status()).isEqualTo(ConnectorHealth.Status.FAILED);
        assertThat(connector.health().errorMessage()).contains("timeout");
    }

    @Test
    void malformedSavedFeedFailsTransformHealth() {
        assertThatThrownBy(() -> connector.importSaved("<rss>broken"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(connector.health().errorStage()).isEqualTo(ConnectorHealth.Stage.TRANSFORM);
    }

    private String savedFeed() throws Exception {
        try (var input = getClass().getResourceAsStream("/newsrss/saved-immigration-feed.xml")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record FixedConnector(ConnectorHealth fixed)
            implements mygrant.ingestion.connector.DataConnector<Object, Object, Object, Object> {
        public SourceIdentity source() { return fixed.source(); }
        public mygrant.ingestion.connector.ConnectorFetch<Object> fetch(Object request) { throw new UnsupportedOperationException(); }
        public Object transform(mygrant.ingestion.connector.ConnectorFetch<Object> fetched, Object request) { throw new UnsupportedOperationException(); }
        public Object persistDeduplicated(Object transformed, Object request) { throw new UnsupportedOperationException(); }
        public ConnectorHealth health() { return fixed; }
        public Object ingest(Object request) { throw new UnsupportedOperationException(); }
    }
}
