package mygrant.ingestion.newsrss;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import mygrant.ingestion.PolicyAlreadyExistsException;
import mygrant.ingestion.PolicyIngestionService;
import mygrant.ingestion.classification.VisaTypeClassifier;
import mygrant.ingestion.connector.ConnectorFetch;
import mygrant.ingestion.connector.ConnectorHealth;
import mygrant.ingestion.connector.ContentHash;
import mygrant.ingestion.connector.DataConnector;
import mygrant.ingestion.connector.SourceIdentity;
import mygrant.ingestion.dto.IngestPolicyRequest;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;
import io.swagger.v3.oas.annotations.media.Schema;

/** Selects student and work-visa news summaries for secondary evidence. */
@Service
public class NewsRssConnector implements DataConnector<NewsRssConnector.Request, String,
        NewsRssParser.ParsedFeed, NewsRssConnector.Result> {
    private static final SourceIdentity SOURCE = new SourceIdentity("NEWS_RSS", SourceType.SECONDARY);
    private final NewsRssClient client;
    private final NewsRssParser parser;
    private final VisaTypeClassifier classifier;
    private final PolicyIngestionService ingestion;
    private final AtomicReference<ConnectorHealth> currentHealth =
            new AtomicReference<>(ConnectorHealth.notChecked(SOURCE));

    public NewsRssConnector(NewsRssClient client, NewsRssParser parser,
            VisaTypeClassifier classifier, PolicyIngestionService ingestion) {
        this.client = client;
        this.parser = parser;
        this.classifier = classifier;
        this.ingestion = ingestion;
    }

    public Result pull() {
        return ingest(new Request(null));
    }

    public Result importSaved(String xml) {
        return ingest(new Request(xml));
    }

    @Override
    public SourceIdentity source() {
        return SOURCE;
    }

    @Override
    public ConnectorFetch<String> fetch(Request request) {
        String xml = request.savedXml() == null ? client.download() : request.savedXml();
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("RSS response is empty");
        }
        return new ConnectorFetch<>(xml, NewsRssClient.FEED_URL, Instant.now(),
                ContentHash.sha256(xml));
    }

    @Override
    public NewsRssParser.ParsedFeed transform(ConnectorFetch<String> fetched, Request request) {
        return parser.parse(fetched.payload());
    }

    @Override
    public Result persistDeduplicated(NewsRssParser.ParsedFeed feed, Request request) {
        int imported = 0;
        int chunksVectorised = 0;
        int duplicates = 0;
        int unrelated = 0;
        List<String> failures = new ArrayList<>();
        for (NewsRssParser.Article article : feed.articles()) {
            String content = article.title() + "\n\n" + article.summary();
            var classification = classifier.classify(article.title(), article.summary());
            if (classification.visaTypes().isEmpty()) {
                unrelated++;
                continue;
            }
            try {
                var saved = ingestion.ingest(new IngestPolicyRequest(
                        "rss:guardian:" + ContentHash.sha256(article.url()),
                        article.title(), "The Guardian",
                        String.join(",", classification.visaTypes()),
                        PolicyStatus.UNKNOWN, source().type(), article.publicationDate(),
                        null, article.url(), content));
                if (saved.alreadyIndexed()) {
                    duplicates++;
                } else {
                    imported++;
                    chunksVectorised += saved.chunksIndexed();
                }
            } catch (PolicyAlreadyExistsException exception) {
                // An edited summary at the same URL must not overwrite old evidence.
                duplicates++;
            } catch (RuntimeException exception) {
                failures.add(article.url() + ": " + exception.getClass().getSimpleName());
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("RSS persistence failed for " + failures.size()
                    + " article(s): " + String.join(", ", failures));
        }
        return new Result(feed.articles().size(), imported, duplicates, unrelated,
                feed.invalidItems(), NewsRssClient.FEED_URL, chunksVectorised);
    }

    @Override
    public ConnectorHealth health() {
        return currentHealth.get();
    }

    @Override
    public Result ingest(Request request) {
        Instant attempt = Instant.now();
        ConnectorHealth.Stage stage = ConnectorHealth.Stage.FETCH;
        try {
            var fetched = fetch(request);
            stage = ConnectorHealth.Stage.TRANSFORM;
            var transformed = transform(fetched, request);
            stage = ConnectorHealth.Stage.PERSIST;
            var result = persistDeduplicated(transformed, request);
            currentHealth.updateAndGet(previous -> previous.succeeded(attempt, Instant.now()));
            return result;
        } catch (RuntimeException exception) {
            ConnectorHealth.Stage failedStage = stage;
            currentHealth.updateAndGet(previous -> previous.failed(attempt, failedStage, exception));
            throw exception;
        }
    }

    public record Request(String savedXml) {}
    @Schema(name = "NewsRssImportResult")
    public record Result(int articles, int imported, int duplicates, int unrelated,
            int invalidItems, String feedUrl, int chunksVectorised) {
        public Result(int articles, int imported, int duplicates, int unrelated,
                int invalidItems, String feedUrl) {
            this(articles, imported, duplicates, unrelated, invalidItems, feedUrl, 0);
        }
    }
}
