package mygrant.ingestion.uscis;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import mygrant.ingestion.connector.ConnectorHealth;

/** Admin-only saved-result import and connector health. */
@RestController
@RequestMapping("/api/v1/ingestion/uscis-processing-times")
@PreAuthorize("hasRole('ADMIN')")
public class UscisProcessingTimeController {
    private final UscisProcessingTimeImporter importer;

    public UscisProcessingTimeController(UscisProcessingTimeImporter importer) {
        this.importer = importer;
    }

    @PostMapping(value = "/saved", consumes = MediaType.APPLICATION_JSON_VALUE)
    public UscisProcessingTimeImporter.Result importSaved(@RequestBody String savedResponse) {
        return importer.ingest(savedResponse);
    }

    @GetMapping("/health")
    public ConnectorHealth health() {
        return importer.health();
    }
}
