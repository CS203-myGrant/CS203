package mygrant.ingestion.newsrss;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Downloads the selected publisher's immigration RSS feed with bounded waits. */
@Component
public class NewsRssClient {
    public static final String FEED_URL = "https://www.theguardian.com/us-news/usimmigration/rss";
    private final RestClient client;

    public NewsRssClient(RestClient.Builder builder) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = builder.clone().requestFactory(factory).build();
    }

    public String download() {
        return client.get().uri(FEED_URL).retrieve().body(String.class);
    }
}
