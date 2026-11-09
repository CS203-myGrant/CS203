package mygrant.ingestion.visabulletin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.MockRestServiceServer.bindTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.YearMonth;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

class VisaBulletinClientTest {
    @Test
    void downloadsRequestedMonthFromCorrectFiscalYear() {
        RestClient.Builder builder = RestClient.builder();
        var server = bindTo(builder).build();
        VisaBulletinClient client = new VisaBulletinClient(builder);
        YearMonth october = YearMonth.of(2026, 10);
        String url = "https://travel.state.gov/content/travel/en/legal/visa-law0/"
                + "visa-bulletin/2027/visa-bulletin-for-october-2026.html";
        assertEquals(url, client.url(october));
        assertEquals("https://travel.state.gov/content/travel/en/legal/visa-law0/"
                + "visa-bulletin/2026/visa-bulletin-for-september-2026.html",
                client.url(YearMonth.of(2026, 9)));
        server.expect(requestTo(url))
                .andRespond(withSuccess("<h1>Visa Bulletin For October 2026</h1>", MediaType.TEXT_HTML));
        assertEquals("<h1>Visa Bulletin For October 2026</h1>", client.download(october));
        server.verify();
    }
}
