package mygrant.ingestion.newsrss;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import mygrant.ingestion.connector.ConnectorHealth;

/** Admin-only RSS pull and saved-response import. */
@RestController
@RequestMapping("/api/v1/ingestion/news-rss")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Ingestion: News RSS")
public class NewsRssController {
    private final NewsRssConnector connector;

    public NewsRssController(NewsRssConnector connector) {
        this.connector = connector;
    }

    @PostMapping("/pull")
    @Operation(summary = "Fetch and import the news feed",
            description = "Imports immigration articles as SECONDARY sources, skipping duplicates and "
                    + "unrelated items.")
    @ApiResponse(responseCode = "200", description = "Imported")
    @ApiResponse(responseCode = "502", description = "The fetch or import failed; health has the details")
    public NewsRssConnector.Result pull() {
        try {
            return connector.pull();
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "RSS import failed; inspect /api/v1/ingestion/news-rss/health");
        }
    }

    @PostMapping(value = "/saved", consumes = { MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE })
    @Operation(summary = "Import a saved news feed",
            description = "The feed's RSS XML, saved from a browser, as `application/xml` or `text/xml`.")
    @ApiResponse(responseCode = "200", description = "Imported")
    @ApiResponse(responseCode = "400", description = "The XML is empty, not an RSS feed, or has no valid articles")
    @ApiResponse(responseCode = "415", description = "The body is not sent as XML")
    public NewsRssConnector.Result importSaved(@RequestBody String xml) {
        return connector.importSaved(xml);
    }

    @GetMapping("/health")
    @Operation(summary = "News feed import health")
    public ConnectorHealth health() {
        return connector.health();
    }
}
