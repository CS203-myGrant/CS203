package mygrant.ingestion.visabulletin;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import mygrant.common.Pages;

/** Admin-only import and inspection of Visa Bulletin cutoff charts. */
@RestController
@RequestMapping("/api/v1/ingestion/visa-bulletin")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Ingestion: Visa Bulletin")
public class VisaBulletinController {
    private final VisaBulletinImporter importer;
    private final VisaBulletinStore store;

    public VisaBulletinController(VisaBulletinImporter importer, VisaBulletinStore store) {
        this.importer = importer;
        this.store = store;
    }

    @PostMapping("/{month}")
    @Operation(summary = "Fetch and import a month's Visa Bulletin",
            description = "Downloads the official page for the publication month and saves its four cutoff "
                    + "charts. Re-importing a month updates it.")
    @ApiResponse(responseCode = "200", description = "Imported")
    @ApiResponse(responseCode = "400", description = "The month is not YYYY-MM")
    @ApiResponse(responseCode = "502", description = "The download or parse failed; health has the details")
    public VisaBulletinImporter.Result importMonth(
            @Parameter(description = "Publication month", example = "2026-10") @PathVariable String month) {
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
    @Operation(summary = "Import a saved Visa Bulletin page",
            description = "For when the Department of State blocks the server download: send the official "
                    + "page saved from a browser, as `text/html`. The month is read from the page.")
    @ApiResponse(responseCode = "200", description = "Imported")
    @ApiResponse(responseCode = "400", description = "The HTML is not a complete Visa Bulletin page")
    public VisaBulletinImporter.Result importSaved(@RequestBody String html) {
        return importer.importSaved(html);
    }

    @GetMapping("/health")
    @Operation(summary = "Visa Bulletin import health",
            description = "The last attempt, the last success and, after a failure, what went wrong.")
    public VisaBulletinStore.Health health() {
        return store.health();
    }

    @GetMapping("/entries/{month}")
    @Operation(summary = "List a month's saved cutoff entries",
            description = "Ordered by preference, chart, category and area; `sort` is ignored. A month has "
                    + "about 150 entries.")
    @ApiResponse(responseCode = "200", description = "A page of entries")
    @ApiResponse(responseCode = "400", description = "The month is not YYYY-MM")
    public Page<VisaBulletinStore.EntryView> entries(
            @Parameter(description = "Publication month", example = "2026-10") @PathVariable String month,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return Pages.of(store.entries(parseMonth(month)), pageable);
    }

    private YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month must be YYYY-MM");
        }
    }
}
