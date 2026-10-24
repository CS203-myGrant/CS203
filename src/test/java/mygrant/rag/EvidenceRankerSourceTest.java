package mygrant.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class EvidenceRankerSourceTest {
    @Test
    void primarySourceOutranksSecondaryNews() {
        Document news = new Document("news", "F-1 news summary",
                Map.of("sourceType", "SECONDARY", "status", "UNKNOWN"));
        Document official = new Document("official", "F-1 official rule",
                Map.of("sourceType", "PRIMARY", "status", "PROPOSED"));

        assertThat(new EvidenceRanker().rank(List.of(news, official)))
                .extracting(Document::getId).containsExactly("official", "news");
    }
}
