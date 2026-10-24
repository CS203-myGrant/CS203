package mygrant.ingestion.visabulletin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import mygrant.ingestion.connector.ConnectorHealth;
import mygrant.ingestion.connector.ContentHash;
import mygrant.ingestion.connector.ConnectorStatusService;

/** Uses PostgreSQL; the import and health changes roll back after this test. */
@SpringBootTest
@Transactional
class VisaBulletinStoreIntegrationTest {
    @Autowired VisaBulletinImporter importer;
    @Autowired VisaBulletinStore store;
    @Autowired VisaBulletinClient client;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConnectorStatusService connectorStatusService;

    @Test
    void realSavedSeptemberPageImportsWithoutChoosingMonthAndRejectsUnrelatedHtml() throws IOException {
        String realPage;
        try (var stream = getClass().getResourceAsStream("/visabulletin/september-2026.html")) {
            realPage = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        YearMonth september = YearMonth.of(2026, 9);
        assertEquals(september, importer.importSaved(realPage).publicationMonth());
        assertEquals(150, store.entries(september).size());
        assertEquals("OK", store.health().status());
        assertEquals(java.util.List.of("FEDERAL_REGISTER", "VISA_BULLETIN"),
                connectorStatusService.status().stream().map(health -> health.source().id()).toList());
        assertEquals(september, store.health().publicationMonth());
        assertEquals(150, importer.importSaved(realPage).entriesProcessed());
        assertEquals(150, store.entries(september).size());

        var error = assertThrows(IllegalArgumentException.class, () ->
                importer.importSaved("<html><h1>School news</h1></html>"));
        assertEquals("This HTML does not look like a Visa Bulletin page.", error.getMessage());
        assertEquals("FAILED", store.health().status());
        assertEquals(150, store.entries(september).size());
    }

    @Test
    void repeatImportDeduplicatesAndBadLayoutPreservesRowsButFailsHealth() throws IOException {
        String sample;
        try (var stream = getClass().getResourceAsStream("/visabulletin/october-2026.html")) {
            sample = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        YearMonth month = YearMonth.of(2026, 10);
        String url = client.url(month);
        assertEquals(150, importer.importSaved(sample, month, url).entriesProcessed());
        Timestamp oldTimestamp = Timestamp.from(Instant.parse("2000-01-01T00:00:00Z"));
        jdbc.update("""
                UPDATE visa_bulletin_entries SET imported_at = ?
                WHERE publication_month = DATE '2026-10-01'
                """, oldTimestamp);
        assertEquals(150, importer.importSaved(sample, month, url).entriesProcessed());
        assertEquals(oldTimestamp, jdbc.queryForObject("""
                SELECT imported_at FROM visa_bulletin_entries
                WHERE publication_month = DATE '2026-10-01' LIMIT 1
                """, Timestamp.class));
        assertEquals(ContentHash.sha256(sample), jdbc.queryForObject("""
                SELECT content_hash FROM visa_bulletin_imports
                WHERE publication_month = DATE '2026-10-01'
                """, String.class).trim());

        String revised = sample.replaceFirst("<td>C</td>", "<td>U</td>");
        assertEquals(150, importer.importSaved(revised, month, url).entriesProcessed());
        assertEquals(ContentHash.sha256(revised), jdbc.queryForObject("""
                SELECT content_hash FROM visa_bulletin_imports
                WHERE publication_month = DATE '2026-10-01'
                """, String.class).trim());
        org.junit.jupiter.api.Assertions.assertNotEquals(oldTimestamp, jdbc.queryForObject("""
                SELECT imported_at FROM visa_bulletin_entries
                WHERE publication_month = DATE '2026-10-01' LIMIT 1
                """, Timestamp.class));
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM visa_bulletin_entries
                WHERE publication_month = DATE '2026-10-01'
                """, Long.class);
        assertEquals(150L, count);
        assertEquals(150, store.entries(month).size());
        assertEquals("OK", store.health().status());

        assertThrows(IllegalArgumentException.class, () -> importer.importSaved(
                "<h1>Visa Bulletin For October 2026</h1>", month, url));
        assertEquals("FAILED", store.health().status());
        assertEquals(ConnectorHealth.Stage.TRANSFORM, store.health().errorStage());
        assertEquals(ConnectorHealth.Stage.TRANSFORM, importer.health().errorStage());
        assertNotNull(store.health().lastSuccessAt());
        assertEquals(count, jdbc.queryForObject("""
                SELECT COUNT(*) FROM visa_bulletin_entries
                WHERE publication_month = DATE '2026-10-01'
                """, Long.class));
    }
}
