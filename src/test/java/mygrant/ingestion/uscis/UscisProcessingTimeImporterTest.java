package mygrant.ingestion.uscis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import mygrant.ingestion.connector.ConnectorHealth;
import mygrant.ingestion.connector.DataConnector;
import mygrant.policies.SourceType;

@ExtendWith(MockitoExtension.class)
class UscisProcessingTimeImporterTest {
    @Mock UscisProcessingTimeStore store;
    private UscisProcessingTimeImporter importer;
    private String sample;

    @BeforeEach
    void setUp() throws IOException {
        try (var stream = getClass().getResourceAsStream("/uscis/saved-i765-result.json")) {
            sample = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        importer = new UscisProcessingTimeImporter(new ObjectMapper(),
                new UscisProcessingTimeParser(), store);
    }

    @Test
    void savedResultKeepsTheReported80thPercentileAndUnits() {
        when(store.save(any())).thenReturn(new UscisProcessingTimeStore.Saved(12L, false));
        DataConnector<String, com.fasterxml.jackson.databind.JsonNode,
                UscisProcessingTimeParser.Observation, UscisProcessingTimeImporter.Result> connector = importer;

        var result = connector.ingest(sample);

        assertEquals("USCIS_PROCESSING_TIMES", connector.source().id());
        assertEquals(SourceType.PRIMARY, connector.source().type());
        assertEquals("I-765", result.formType());
        assertEquals("Service Center Operations (SCOPS)", result.office());
        assertEquals(80, result.percentile());
        assertEquals(new BigDecimal("4.5"), result.percentileValue());
        assertEquals("MONTHS", result.durationUnit());
        assertEquals(12L, result.snapshotId());
    }

    @Test
    void missingCategoryOrMetricFailsAtTransformWithoutSaving() {
        String missingCategory = sample.replace("\"formCategory\": \"(c)(3)(B) Post-completion OPT\",", "");
        assertThrows(IllegalArgumentException.class, () -> importer.ingest(missingCategory));
        verify(store).fail(eq(ConnectorHealth.Stage.TRANSFORM), any());
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void malformedJsonAndUntrustedSourceFailAtFetch() {
        assertThrows(IllegalArgumentException.class, () -> importer.ingest("{not-json"));
        assertThrows(IllegalArgumentException.class, () -> importer.ingest(
                sample.replace("egov.uscis.gov", "egov.uscis.gov.evil.example")));
        verify(store, org.mockito.Mockito.times(2)).fail(eq(ConnectorHealth.Stage.FETCH), any());
    }

    @Test
    void changedMetricLayoutIsRejectedRatherThanGuessing() {
        String changed = sample.replace("80% of cases are completed within 4.5 Months",
                "average time is 4.5 months");
        var error = assertThrows(IllegalArgumentException.class, () -> importer.ingest(changed));
        assertTrue(error.getMessage().contains("80% time"));
        verify(store).fail(eq(ConnectorHealth.Stage.TRANSFORM), any());
    }

    @Test
    void daysRemainDaysWithoutApproximateConversion() {
        when(store.save(any())).thenReturn(new UscisProcessingTimeStore.Saved(13L, false));
        var result = importer.ingest(sample.replace("4.5 Months", "45 Days"));
        assertEquals(new BigDecimal("45"), result.percentileValue());
        assertEquals("DAYS", result.durationUnit());
    }

    @Test
    void otherFormPrefixesWorkAndMixedCardsAreRejected() {
        when(store.save(any())).thenReturn(new UscisProcessingTimeStore.Saved(14L, false));
        var naturalization = importer.ingest(sample.replace("I-765", "N-400"));
        assertEquals("N-400", naturalization.formType());

        String excerpt = "Processing time for Application for Employment Authorization (I-765) "
                + "at Service Center Operations (SCOPS)\\n80% of cases are completed within 4.5 Months";
        String mixed = sample.replace("4.5 Months", "4.5 Months\\n" + excerpt);
        assertThrows(IllegalArgumentException.class, () -> importer.ingest(mixed));
    }
}
