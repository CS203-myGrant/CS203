package mygrant.ingestion.visabulletin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import mygrant.ingestion.connector.DataConnector;
import mygrant.policies.SourceType;

@ExtendWith(MockitoExtension.class)
class VisaBulletinParserTest {
    private static final YearMonth MONTH = YearMonth.of(2026, 10);
    private static final String URL = "https://travel.state.gov/content/travel/en/legal/visa-law0/"
            + "visa-bulletin/2027/visa-bulletin-for-october-2026.html";
    private final VisaBulletinParser parser = new VisaBulletinParser();
    private String sample;
    @Mock private VisaBulletinClient client;
    @Mock private VisaBulletinStore store;

    @BeforeEach
    void load() throws IOException {
        try (var stream = getClass().getResourceAsStream("/visabulletin/october-2026.html")) {
            sample = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void extractsAllFourChartsAndKeepsCurrentUnavailableAndDateSeparate() {
        VisaBulletinParser.Bulletin bulletin = parser.parse(sample, MONTH, URL);
        assertEquals(MONTH, bulletin.publicationMonth());
        assertEquals(150, bulletin.entries().size());
        var mexico = bulletin.entries().stream().filter(entry ->
                entry.preference().equals("FAMILY") && entry.chart().equals("FINAL_ACTION")
                && entry.category().equals("F1") && entry.chargeabilityArea().equals("MEXICO"))
                .findFirst().orElseThrow();
        assertEquals("DATE", mexico.cutoffKind());
        assertEquals(LocalDate.of(2008, 9, 8), mexico.cutoffDate());
        var current = bulletin.entries().stream().filter(entry ->
                entry.preference().equals("FAMILY") && entry.chart().equals("DATES_FOR_FILING")
                && entry.category().equals("F2A") && entry.chargeabilityArea().equals("INDIA"))
                .findFirst().orElseThrow();
        assertEquals("CURRENT", current.cutoffKind());
        assertNull(current.cutoffDate());
        var unavailable = parser.parse(sample.replaceFirst("<td>C</td>", "<td>U</td>"), MONTH, URL);
        assertTrue(unavailable.entries().stream().anyMatch(entry ->
                entry.cutoffKind().equals("UNAVAILABLE") && entry.cutoffDate() == null));
    }

    @Test
    void detectsMonthAndParsesRealSavedSeptemberPage() throws IOException {
        String realPage;
        try (var stream = getClass().getResourceAsStream("/visabulletin/september-2026.html")) {
            realPage = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var bulletin = parser.parse(realPage, "");
        assertEquals(YearMonth.of(2026, 9), bulletin.publicationMonth());
        assertEquals(150, bulletin.entries().size());
        assertTrue(bulletin.entries().stream().anyMatch(entry ->
                entry.cutoffKind().equals("UNAVAILABLE")));
    }

    @Test
    void unrelatedHtmlIsRejectedClearly() {
        var error = assertThrows(IllegalArgumentException.class, () ->
                parser.parse("<html><h1>School news</h1><p>Hello</p></html>", ""));
        assertEquals("This HTML does not look like a Visa Bulletin page.", error.getMessage());
    }

    @Test
    void missingChartChangedColumnAndUnexpectedValueAreRejected() {
        String incomplete = sample.substring(0,
                sample.indexOf("<p>B. DATES FOR FILING OF EMPLOYMENT-BASED")) + "</body></html>";
        assertThrows(IllegalArgumentException.class, () -> parser.parse(incomplete, MONTH, URL));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(
                sample.replaceFirst("CHINA-mainland born", "UNKNOWN COUNTRY"), MONTH, URL));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(
                sample.replaceFirst("22JAN20", "22XYZ20"), MONTH, URL));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(
                sample, YearMonth.of(2026, 11), URL));
    }

    @Test
    void invalidSavedPageMarksHealthFailedWithoutSavingEntries() {
        VisaBulletinImporter importer = new VisaBulletinImporter(client, parser, store);
        assertThrows(IllegalArgumentException.class, () -> importer.importSaved(
                "<h1>Visa Bulletin For October 2026</h1>"));
        verify(store, never()).save(any());
        verify(store).fail(any());
    }

    @Test
    void downloadFailureMarksHealthFailed() {
        when(client.url(MONTH)).thenReturn(URL);
        when(client.download(MONTH)).thenThrow(new IllegalStateException("HTTP 403"));
        VisaBulletinImporter importer = new VisaBulletinImporter(client, parser, store);
        assertThrows(IllegalStateException.class, () -> importer.importMonth(MONTH));
        verify(store).fail("HTTP 403");
        verify(store, never()).save(any());
    }

    @Test
    void liveImportUsesSharedConnectorContract() {
        when(client.url(MONTH)).thenReturn(URL);
        when(client.download(MONTH)).thenReturn(sample);
        when(store.save(any())).thenReturn(150);
        DataConnector<YearMonth, String, VisaBulletinParser.Bulletin, VisaBulletinImporter.Result> connector =
                new VisaBulletinImporter(client, parser, store);

        var result = connector.ingest(MONTH);

        assertEquals("VISA_BULLETIN", connector.source().id());
        assertEquals(SourceType.PRIMARY, connector.source().type());
        assertEquals(MONTH, result.publicationMonth());
        assertEquals(150, result.entriesProcessed());
        verify(store).save(any());
    }
}
