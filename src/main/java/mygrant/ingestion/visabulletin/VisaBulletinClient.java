package mygrant.ingestion.visabulletin;

import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Locale;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Downloads one requested month from the official Department of State site. */
@Component
public class VisaBulletinClient {
    private final RestClient http;

    public VisaBulletinClient(RestClient.Builder builder) {
        this.http = builder.build();
    }

    public String url(YearMonth month) {
        int fiscalYear = month.getMonthValue() >= 10 ? month.getYear() + 1 : month.getYear();
        String name = month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                .toLowerCase(Locale.ENGLISH);
        return "https://travel.state.gov/content/travel/en/legal/visa-law0/visa-bulletin/"
                + fiscalYear + "/visa-bulletin-for-" + name + "-" + month.getYear() + ".html";
    }

    public String download(YearMonth month) {
        String html = http.get().uri(url(month)).retrieve().body(String.class);
        if (html == null || html.isBlank()) {
            throw new IllegalStateException("Visa Bulletin page was empty");
        }
        return html;
    }
}
