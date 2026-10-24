package mygrant.ingestion.visabulletin;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Admin-only import and inspection of Visa Bulletin cutoff charts. */
@RestController
@RequestMapping("/api/v1/ingestion/visa-bulletin")
@PreAuthorize("hasRole('ADMIN')")
public class VisaBulletinController {
    private final VisaBulletinImporter importer;
    private final VisaBulletinStore store;

    public VisaBulletinController(VisaBulletinImporter importer, VisaBulletinStore store) {
        this.importer = importer;
        this.store = store;
    }

    @PostMapping("/{month}")
    public VisaBulletinImporter.Result importMonth(@PathVariable String month) {
        YearMonth requested = parseMonth(month);
        try {
            return importer.importMonth(requested);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Visa Bulletin import failed; inspect /api/v1/ingestion/visa-bulletin/health");
        }
    }

    /** Saved HTML identifies its own month; invalid pages return a clear 400 error. */
    @PostMapping(value = "/saved", consumes = MediaType.TEXT_HTML_VALUE)
    public VisaBulletinImporter.Result importSaved(@RequestBody String html) {
        return importer.importSaved(html);
    }

    @GetMapping("/health")
    public VisaBulletinStore.Health health() {
        return store.health();
    }

    @GetMapping("/entries/{month}")
    public List<VisaBulletinStore.EntryView> entries(@PathVariable String month) {
        return store.entries(parseMonth(month));
    }

    private YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month must be YYYY-MM");
        }
    }
}
