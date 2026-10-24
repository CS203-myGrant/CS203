package mygrant.ingestion.visabulletin;

import java.time.YearMonth;
import java.time.Instant;

import org.springframework.stereotype.Service;

import mygrant.ingestion.connector.ConnectorFetch;
import mygrant.ingestion.connector.ConnectorHealth;
import mygrant.ingestion.connector.ContentHash;
import mygrant.ingestion.connector.DataConnector;
import mygrant.ingestion.connector.SourceIdentity;
import mygrant.policies.SourceType;

@Service
public class VisaBulletinImporter implements DataConnector<YearMonth, String,
        VisaBulletinParser.Bulletin, VisaBulletinImporter.Result> {
    private static final SourceIdentity SOURCE = new SourceIdentity("VISA_BULLETIN", SourceType.PRIMARY);
    private final VisaBulletinClient client;
    private final VisaBulletinParser parser;
    private final VisaBulletinStore store;

    public VisaBulletinImporter(VisaBulletinClient client, VisaBulletinParser parser,
            VisaBulletinStore store) {
        this.client = client;
        this.parser = parser;
        this.store = store;
    }

    public Result importMonth(YearMonth month) {
        return ingest(month);
    }

    @Override
    public SourceIdentity source() {
        return SOURCE;
    }

    @Override
    public ConnectorFetch<String> fetch(YearMonth month) {
        String url = client.url(month);
        String html = client.download(month);
        return new ConnectorFetch<>(html, url, Instant.now(), ContentHash.sha256(html));
    }

    @Override
    public VisaBulletinParser.Bulletin transform(ConnectorFetch<String> fetched, YearMonth month) {
        return parser.parse(fetched.payload(), month, fetched.sourceUrl());
    }

    @Override
    public Result persistDeduplicated(VisaBulletinParser.Bulletin bulletin, YearMonth month) {
        return new Result(bulletin.publicationMonth(), store.save(bulletin), bulletin.sourceUrl());
    }

    @Override
    public ConnectorHealth health() {
        VisaBulletinStore.Health saved = store.health();
        return new ConnectorHealth(source(), ConnectorHealth.Status.valueOf(saved.status()),
                saved.lastAttemptAt(), saved.lastSuccessAt(), null, saved.errorMessage());
    }

    @Override
    public Result ingest(YearMonth month) {
        try {
            ConnectorFetch<String> fetched = fetch(month);
            return persistDeduplicated(transform(fetched, month), month);
        } catch (RuntimeException exception) {
            store.fail(exception.getMessage());
            throw exception;
        }
    }

    /** Detects the month in a saved page and records the corresponding official source URL. */
    public Result importSaved(String html) {
        try {
            VisaBulletinParser.Bulletin parsed = parser.parse(html, "");
            YearMonth month = parsed.publicationMonth();
            String sourceUrl = client.url(month);
            VisaBulletinParser.Bulletin bulletin = new VisaBulletinParser.Bulletin(
                    month, sourceUrl, parsed.entries());
            return persistDeduplicated(bulletin, month);
        } catch (RuntimeException exception) {
            store.fail(exception.getMessage());
            throw exception;
        }
    }

    /** Checks the requested month when the page came from the live month-specific URL. */
    public Result importSaved(String html, YearMonth month, String sourceUrl) {
        try {
            ConnectorFetch<String> fetched = new ConnectorFetch<>(html, sourceUrl, Instant.now(),
                    ContentHash.sha256(html));
            return persistDeduplicated(transform(fetched, month), month);
        } catch (RuntimeException exception) {
            store.fail(exception.getMessage());
            throw exception;
        }
    }

    public record Result(YearMonth publicationMonth, int entriesProcessed, String sourceUrl) {}
}
