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
import io.swagger.v3.oas.annotations.media.Schema;

@Service
public class VisaBulletinImporter implements DataConnector<YearMonth, String,
        VisaBulletinImporter.PreparedBulletin, VisaBulletinImporter.Result> {
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
    public PreparedBulletin transform(ConnectorFetch<String> fetched, YearMonth month) {
        return new PreparedBulletin(parser.parse(fetched.payload(), month, fetched.sourceUrl()),
                fetched.contentHash());
    }

    @Override
    public Result persistDeduplicated(PreparedBulletin prepared, YearMonth month) {
        VisaBulletinParser.Bulletin bulletin = prepared.bulletin();
        return new Result(bulletin.publicationMonth(),
                store.save(bulletin, prepared.contentHash()), bulletin.sourceUrl());
    }

    @Override
    public ConnectorHealth health() {
        VisaBulletinStore.Health saved = store.health();
        return new ConnectorHealth(source(), ConnectorHealth.Status.valueOf(saved.status()),
                saved.lastAttemptAt(), saved.lastSuccessAt(), saved.errorStage(), saved.errorMessage());
    }

    @Override
    public Result ingest(YearMonth month) {
        ConnectorHealth.Stage stage = ConnectorHealth.Stage.FETCH;
        try {
            ConnectorFetch<String> fetched = fetch(month);
            stage = ConnectorHealth.Stage.TRANSFORM;
            PreparedBulletin prepared = transform(fetched, month);
            stage = ConnectorHealth.Stage.PERSIST;
            return persistDeduplicated(prepared, month);
        } catch (RuntimeException exception) {
            store.fail(stage, exception.getMessage());
            throw exception;
        }
    }

    /** Detects the month in a saved page and records the corresponding official source URL. */
    public Result importSaved(String html) {
        ConnectorHealth.Stage stage = ConnectorHealth.Stage.TRANSFORM;
        try {
            VisaBulletinParser.Bulletin parsed = parser.parse(html, "");
            YearMonth month = parsed.publicationMonth();
            String sourceUrl = client.url(month);
            VisaBulletinParser.Bulletin bulletin = new VisaBulletinParser.Bulletin(
                    month, sourceUrl, parsed.entries());
            stage = ConnectorHealth.Stage.PERSIST;
            return persistDeduplicated(new PreparedBulletin(bulletin, ContentHash.sha256(html)), month);
        } catch (RuntimeException exception) {
            store.fail(stage, exception.getMessage());
            throw exception;
        }
    }

    /** Checks the requested month when the page came from the live month-specific URL. */
    public Result importSaved(String html, YearMonth month, String sourceUrl) {
        ConnectorHealth.Stage stage = ConnectorHealth.Stage.TRANSFORM;
        try {
            ConnectorFetch<String> fetched = new ConnectorFetch<>(html, sourceUrl, Instant.now(),
                    ContentHash.sha256(html));
            PreparedBulletin prepared = transform(fetched, month);
            stage = ConnectorHealth.Stage.PERSIST;
            return persistDeduplicated(prepared, month);
        } catch (RuntimeException exception) {
            store.fail(stage, exception.getMessage());
            throw exception;
        }
    }

    @Schema(name = "VisaBulletinImportResult")
    public record Result(YearMonth publicationMonth, int entriesProcessed, String sourceUrl) {}

    public record PreparedBulletin(VisaBulletinParser.Bulletin bulletin, String contentHash) {}
}
