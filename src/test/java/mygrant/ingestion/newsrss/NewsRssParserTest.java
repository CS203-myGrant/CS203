package mygrant.ingestion.newsrss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class NewsRssParserTest {
    private final NewsRssParser parser = new NewsRssParser();

    @Test
    void readsSavedRssAndSkipsInvalidItems() throws Exception {
        String xml;
        try (var input = getClass().getResourceAsStream("/newsrss/saved-immigration-feed.xml")) {
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var parsed = parser.parse(xml);
        assertThat(parsed.articles()).hasSize(3);
        assertThat(parsed.invalidItems()).isEqualTo(1);
        assertThat(parsed.articles().get(0).publicationDate()).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(parsed.articles().get(0).summary()).doesNotContain("<p>");
        assertThat(parsed.articles().get(1).url()).isEqualTo(
                "https://www.theguardian.com/us-news/2026/oct/22/work-visas");
    }

    @Test
    void rejectsMalformedNonRssAndExternalEntityPayloads() {
        assertThatThrownBy(() -> parser.parse("<rss><channel>"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("<html><body>not rss</body></html>"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not an RSS");
        assertThatThrownBy(() -> parser.parse("""
                <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <rss><channel><item><title>&xxe;</title></item></channel></rss>
                """))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
