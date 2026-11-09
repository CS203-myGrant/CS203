package mygrant.ingestion.uscis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import mygrant.ingestion.connector.ConnectorHealth;
import mygrant.ingestion.connector.ConnectorStatusService;

@SpringBootTest
@Transactional
class UscisProcessingTimeStoreIntegrationTest {
    @Autowired UscisProcessingTimeImporter importer;
    @Autowired ConnectorStatusService statusService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void observationsAreAppendOnlyIdempotentAndNeverInventMedian() throws IOException {
        String sample;
        try (var stream = getClass().getResourceAsStream("/uscis/saved-i765-result.json")) {
            sample = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var first = importer.ingest(sample);
        var duplicate = importer.ingest(sample);
        assertFalse(first.duplicate());
        assertTrue(duplicate.duplicate());
        assertEquals(first.snapshotId(), duplicate.snapshotId());
        assertEquals(1L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM processing_time_snapshots WHERE id = ?
                """, Long.class, first.snapshotId()));
        assertEquals(80, jdbc.queryForObject("""
                SELECT percentile FROM processing_time_snapshots WHERE id = ?
                """, Integer.class, first.snapshotId()));
        assertNull(jdbc.queryForObject("""
                SELECT median_value FROM processing_time_snapshots WHERE id = ?
                """, java.math.BigDecimal.class, first.snapshotId()));
        assertNotNull(jdbc.queryForObject("""
                SELECT source_content_hash FROM processing_time_snapshots WHERE id = ?
                """, String.class, first.snapshotId()));
        assertTrue(jdbc.queryForObject("""
                SELECT source_excerpt FROM processing_time_snapshots WHERE id = ?
                """, String.class, first.snapshotId()).contains("80% of cases"));
        assertEquals(ConnectorHealth.Status.OK, importer.health().status());
        assertTrue(statusService.status().stream().anyMatch(status ->
                status.source().id().equals("USCIS_PROCESSING_TIMES")));

        var later = importer.ingest(sample.replace("2026-09-15", "2026-09-16"));
        assertFalse(later.duplicate());
        assertEquals(2L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM processing_time_snapshots
                WHERE form_type = 'I-765' AND form_category = '(c)(3)(B) Post-completion OPT'
                """, Long.class));
    }

    @Test
    void malformedCaptureFailsHealthAndPreservesPreviousObservation() throws IOException {
        String sample;
        try (var stream = getClass().getResourceAsStream("/uscis/saved-i765-result.json")) {
            sample = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var first = importer.ingest(sample);
        assertThrows(IllegalArgumentException.class, () -> importer.ingest(
                sample.replace("80% of cases are completed within 4.5 Months", "No data")));
        assertEquals(ConnectorHealth.Status.FAILED, importer.health().status());
        assertEquals(ConnectorHealth.Stage.TRANSFORM, importer.health().errorStage());
        assertNotNull(importer.health().lastSuccessAt());
        assertEquals(1L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM processing_time_snapshots WHERE id = ?
                """, Long.class, first.snapshotId()));
    }

    @Test
    void changedValueOnSameDayAddsHistoryWithoutRewritingEarlierValue() throws IOException {
        String sample;
        try (var stream = getClass().getResourceAsStream("/uscis/saved-i765-result.json")) {
            sample = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var original = importer.ingest(sample);
        var changed = importer.ingest(sample.replace("4.5 Months", "6 Months"));
        assertFalse(changed.duplicate());
        assertEquals(2L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM processing_time_snapshots
                WHERE observed_on = DATE '2026-09-15' AND form_type = 'I-765'
                """, Long.class));
        assertEquals(0, jdbc.queryForObject("""
                SELECT percentile_value FROM processing_time_snapshots WHERE id = ?
                """, java.math.BigDecimal.class, original.snapshotId())
                .compareTo(new java.math.BigDecimal("4.5")));
    }
}
