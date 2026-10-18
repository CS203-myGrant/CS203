package mygrant.rag;

import java.util.Comparator;
import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

@Component
public class EvidenceRanker {

    public List<Document> rank(List<Document> evidence) {
        return evidence.stream()
                .sorted(Comparator.comparingInt(this::authorityRank)
                        .reversed()
                        .thenComparing(Document::getScore,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private int authorityRank(Document document) {
        String sourceType = metadata(document, "sourceType");
        String status = metadata(document, "status");

        int rank = "PRIMARY".equals(sourceType) ? 10 : 0;
        return rank + switch (status) {
            case "EFFECTIVE" -> 5;
            case "FINAL" -> 4;
            case "GUIDANCE" -> 3;
            case "PROPOSED" -> 1;
            default -> 0;
        };
    }

    private String metadata(Document document, String key) {
        return String.valueOf(document.getMetadata().getOrDefault(key, ""));
    }
}
