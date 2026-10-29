package mygrant.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import mygrant.common.Confidence;
import mygrant.ai.OpenAiGenerationService;
import mygrant.impact.ImpactJudgeService;
import mygrant.impact.JudgeEvaluation;
import mygrant.rag.dto.CitationResponse;
import mygrant.rag.dto.RagQueryRequest;
import mygrant.rag.dto.RagQueryResponse;
import mygrant.rag.dto.RetrievedEvidenceResponse;

/** Retrieves visa-filtered policy evidence and generates cited, review-aware answers. */
@Service
public class RagService {

    private static final Set<String> SUPPORTED_VISA_TYPES = Set.of("F-1", "J-1", "H-1B");
    private static final String DISCLAIMER = "This is informational guidance and not legal advice.";

    private final VectorStore vectorStore;
    private final OpenAiGenerationService generator;
    private final EvidenceRanker evidenceRanker;
    private final ImpactJudgeService judge;

    public RagService(VectorStore vectorStore, OpenAiGenerationService generator,
            EvidenceRanker evidenceRanker, ImpactJudgeService judge) {
        this.vectorStore = vectorStore;
        this.generator = generator;
        this.evidenceRanker = evidenceRanker;
        this.judge = judge;
    }

    /** Answers from retrieved evidence or returns an insufficient-evidence response. */
    public RagQueryResponse query(RagQueryRequest request) {
        String visaType = validateVisaType(request.visaType());
        SearchRequest searchRequest = SearchRequest.builder()
                .query(request.question())
                .topK(5)
                .similarityThreshold(0.55)
                .filterExpression("visaType == '" + visaType + "'")
                .build();

        List<Document> evidence = evidenceRanker.rank(vectorStore.similaritySearch(searchRequest));
        if (evidence.isEmpty()) {
            return insufficientEvidence();
        }

        Assessment assessment = assess(evidence);
        String context = "User visa type: " + visaType + "\nEvidence:\n" + formatEvidence(evidence)
                + "\nQuestion: " + request.question();
        String answer = generator.generate("""
                        You are myGRANT, an immigration policy information system.
                        Answer only from the supplied evidence.
                        Never invent dates, requirements, exceptions, or legal outcomes.
                        Clearly distinguish proposed, final, effective, challenged, and guidance sources.
                        If the evidence is incomplete or conflicting, explicitly say so.
                        Refer to evidence using its bracketed number, such as [1].
                        Do not describe the response as legal advice.
                        """, context);

        List<CitationResponse> citations = createCitations(evidence);
        JudgeEvaluation evaluation = judge.evaluateAnswer(context, answer);
        if (evaluation.verdict() != JudgeEvaluation.Verdict.YES) {
            return new RagQueryResponse("An independently verified answer is not available. Please try again or contact the support team.",
                    Confidence.LOW, true, citations, createRetrievedEvidence(evidence, citations),
                    List.of("Independent review: " + evaluation.verdict(), DISCLAIMER), evaluation);
        }
        return new RagQueryResponse(answer, assessment.confidence(), assessment.requiresReview(),
                citations, createRetrievedEvidence(evidence, citations), assessment.warnings(), evaluation);
    }

    private String formatEvidence(List<Document> evidence) {
        StringBuilder context = new StringBuilder();
        for (int index = 0; index < evidence.size(); index++) {
            Document document = evidence.get(index);
            context.append("[%d] %s | %s | %s%n%s%n%n".formatted(
                    index + 1,
                    metadata(document, "title"),
                    metadata(document, "status"),
                    metadata(document, "sourceUrl"),
                    document.getText()));
        }
        return context.toString();
    }

    private List<CitationResponse> createCitations(List<Document> evidence) {
        Map<String, CitationResponse> citations = new LinkedHashMap<>();
        for (Document document : evidence) {
            String externalId = metadata(document, "externalId");
            citations.putIfAbsent(externalId, new CitationResponse(
                    externalId,
                    metadata(document, "title"),
                    metadata(document, "agency"),
                    metadata(document, "status"),
                    metadata(document, "sourceType"),
                    metadata(document, "publicationDate"),
                    optionalMetadata(document, "effectiveDate"),
                    metadata(document, "sourceUrl"),
                    document.getScore()));
        }
        return List.copyOf(citations.values());
    }

    private List<RetrievedEvidenceResponse> createRetrievedEvidence(List<Document> evidence,
            List<CitationResponse> citations) {
        Map<String, Integer> citationNumbers = new LinkedHashMap<>();
        for (int index = 0; index < citations.size(); index++) {
            citationNumbers.put(citations.get(index).externalId(), index + 1);
        }

        return evidence.stream()
                .map(document -> new RetrievedEvidenceResponse(
                        citationNumbers.getOrDefault(metadata(document, "externalId"), 0),
                        metadata(document, "externalId"),
                        metadata(document, "title"),
                        metadata(document, "agency"),
                        metadata(document, "status"),
                        metadata(document, "sourceType"),
                        metadata(document, "sourceUrl"),
                        document.getScore(),
                        evidenceSnippet(document.getText())))
                .toList();
    }

    private Assessment assess(List<Document> evidence) {
        boolean hasPrimary = evidence.stream()
                .anyMatch(document -> "PRIMARY".equals(metadata(document, "sourceType")));
        boolean uncertainStatus = evidence.stream()
                .anyMatch(document -> Set.of("PROPOSED", "CHALLENGED", "UNKNOWN")
                        .contains(metadata(document, "status")));
        double bestScore = evidence.stream()
                .map(Document::getScore)
                .filter(score -> score != null)
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0.0);

        List<String> warnings = new ArrayList<>();
        warnings.add(DISCLAIMER);
        if (!hasPrimary) {
            warnings.add("No primary government source was retrieved.");
        }
        if (uncertainStatus) {
            warnings.add("At least one source is proposed, challenged, or has an unknown status.");
        }

        boolean requiresReview = !hasPrimary || uncertainStatus || bestScore < 0.80;
        Confidence confidence = !requiresReview && bestScore >= 0.85
                ? Confidence.HIGH
                : bestScore >= 0.75 ? Confidence.MEDIUM : Confidence.LOW;
        return new Assessment(confidence, requiresReview, List.copyOf(warnings));
    }

    private RagQueryResponse insufficientEvidence() {
        return new RagQueryResponse(
                "I could not find sufficient policy evidence to answer this question.",
                Confidence.LOW,
                true,
                List.of(),
                List.of(),
                List.of("Human review is required.", DISCLAIMER), null);
    }

    private String evidenceSnippet(String text) {
        String normalized = text == null ? "" : text
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.length() <= 800 ? normalized : normalized.substring(0, 800) + "...";
    }

    private String validateVisaType(String visaType) {
        String normalized = visaType.trim().toUpperCase();
        if (!SUPPORTED_VISA_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported visa type: " + visaType);
        }
        return normalized;
    }

    private String metadata(Document document, String key) {
        Object value = document.getMetadata().get(key);
        if (value == null) {
            throw new IllegalStateException("Retrieved document is missing metadata: " + key);
        }
        return value.toString();
    }

    private String optionalMetadata(Document document, String key) {
        Object value = document.getMetadata().get(key);
        return value == null ? null : value.toString();
    }

    private record Assessment(Confidence confidence, boolean requiresReview, List<String> warnings) {
    }
}
