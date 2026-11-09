package mygrant.ingestion.newsrss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.HttpStatus;

import mygrant.ingestion.connector.ConnectorHealth;
import mygrant.ingestion.connector.ConnectorStatusService;
import mygrant.ingestion.uscis.UscisProcessingTimeImporter;

@SpringBootTest
@Transactional
class NewsRssStoreIntegrationTest {
    @Autowired NewsRssConnector connector;
    @Autowired ConnectorStatusService status;
    @Autowired JdbcTemplate jdbc;
    @Autowired UscisProcessingTimeImporter uscis;
    @MockBean VectorStore vectorStore;
    @MockBean NewsRssClient client;

    @Test
    void savedFeedPersistsSecondaryDocumentsOnceAndAllConnectorsReportHealth() throws Exception {
        String xml;
        try (var input = getClass().getResourceAsStream("/newsrss/saved-immigration-feed.xml")) {
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var first = connector.importSaved(xml);
        var repeat = connector.importSaved(xml);

        assertThat(first.imported()).isEqualTo(2);
        assertThat(repeat.duplicates()).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM policy_documents
                WHERE external_id LIKE 'rss:guardian:%' AND source_type = 'SECONDARY'
                """, Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
                SELECT publication_date FROM policy_documents
                WHERE source_url = 'https://www.theguardian.com/us-news/2026/oct/21/student-visas'
                """, java.sql.Date.class).toLocalDate()).isEqualTo(
                java.time.LocalDate.of(2026, 10, 21));
        assertThat(status.status()).extracting(health -> health.source().id())
                .contains("FEDERAL_REGISTER", "NEWS_RSS", "USCIS_PROCESSING_TIMES", "VISA_BULLETIN");
        assertThat(connector.health().status()).isEqualTo(ConnectorHealth.Status.OK);

        when(client.download()).thenThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS));
        org.junit.jupiter.api.Assertions.assertThrows(HttpClientErrorException.class, connector::pull);
        assertThat(status.status()).filteredOn(health -> health.source().id().equals("NEWS_RSS"))
                .singleElement().satisfies(health -> {
                    assertThat(health.status()).isEqualTo(ConnectorHealth.Status.FAILED);
                    assertThat(health.errorStage()).isEqualTo(ConnectorHealth.Stage.FETCH);
                    assertThat(health.lastSuccessAt()).isNotNull();
                });
        assertThat(status.status()).filteredOn(health -> health.source().id().equals("FEDERAL_REGISTER"))
                .singleElement().satisfies(health -> assertThat(health.status())
                        .isNotEqualTo(ConnectorHealth.Status.FAILED));

        String uscisSample;
        try (var input = getClass().getResourceAsStream("/uscis/saved-i765-result.json")) {
            uscisSample = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(uscis.ingest(uscisSample).duplicate()).isFalse();
        assertThat(uscis.health().status()).isEqualTo(ConnectorHealth.Status.OK);
        assertThat(connector.health().status()).isEqualTo(ConnectorHealth.Status.FAILED);
    }
}
