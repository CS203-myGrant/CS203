package mygrant.ingestion.visabulletin;

import java.time.YearMonth;

import org.springframework.stereotype.Service;

@Service
public class VisaBulletinImporter {
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
        String url = client.url(month);
        String html;
        try {
            html = client.download(month);
        } catch (RuntimeException exception) {
            store.fail(exception.getMessage());
            throw exception;
        }
        return importSaved(html, month, url);
    }

    /** Detects the month in a saved page and records the corresponding official source URL. */
    public Result importSaved(String html) {
        try {
            VisaBulletinParser.Bulletin parsed = parser.parse(html, "");
            YearMonth month = parsed.publicationMonth();
            String sourceUrl = client.url(month);
            VisaBulletinParser.Bulletin bulletin = new VisaBulletinParser.Bulletin(
                    month, sourceUrl, parsed.entries());
            return new Result(month, store.save(bulletin), sourceUrl);
        } catch (RuntimeException exception) {
            store.fail(exception.getMessage());
            throw exception;
        }
    }

    /** Checks the requested month when the page came from the live month-specific URL. */
    public Result importSaved(String html, YearMonth month, String sourceUrl) {
        try {
            VisaBulletinParser.Bulletin bulletin = parser.parse(html, month, sourceUrl);
            return new Result(month, store.save(bulletin), sourceUrl);
        } catch (RuntimeException exception) {
            store.fail(exception.getMessage());
            throw exception;
        }
    }

    public record Result(YearMonth publicationMonth, int entriesProcessed, String sourceUrl) {}
}
