package mygrant.ingestion.uscis;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import mygrant.ingestion.connector.ConnectorHealth;

/** Admin-only saved-result import and connector health. */
@RestController
@RequestMapping("/api/v1/ingestion/uscis-processing-times")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Ingestion: USCIS processing times")
public class UscisProcessingTimeController {
    private final UscisProcessingTimeImporter importer;

    public UscisProcessingTimeController(UscisProcessingTimeImporter importer) {
        this.importer = importer;
    }

    @PostMapping(value = "/saved", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Import a saved USCIS processing-time result",
            description = "The JSON saved from the official USCIS processing-times page, with its "
                    + "`sourceUrl` and `sourceExcerpt`. An identical observation is reported as a duplicate.")
    @ApiResponse(responseCode = "200", description = "Imported, or recognised as a duplicate")
    @ApiResponse(responseCode = "400", description = "The JSON is invalid, incomplete or not from an official "
            + "USCIS processing-times URL")
    @ApiResponse(responseCode = "415", description = "The body is not sent as JSON")
    public UscisProcessingTimeImporter.Result importSaved(@RequestBody String savedResponse) {
        return importer.ingest(savedResponse);
    }

    @GetMapping("/health")
    @Operation(summary = "USCIS processing-time import health")
    public ConnectorHealth health() {
        return importer.health();
    }
}
