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

import mygrant.ingestion.connector.ConnectorHealth;

/** Admin-only RSS pull and saved-response import. */
@RestController
@RequestMapping("/api/v1/ingestion/news-rss")
@PreAuthorize("hasRole('ADMIN')")
public class NewsRssController {
    private final NewsRssConnector connector;

    public NewsRssController(NewsRssConnector connector) {
        this.connector = connector;
    }

    @PostMapping("/pull")
    public NewsRssConnector.Result pull() {
        try {
            return connector.pull();
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "RSS import failed; inspect /api/v1/ingestion/news-rss/health");
        }
    }

    @PostMapping(value = "/saved", consumes = { MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE })
    public NewsRssConnector.Result importSaved(@RequestBody String xml) {
        return connector.importSaved(xml);
    }

    @GetMapping("/health")
    public ConnectorHealth health() {
        return connector.health();
    }
}
