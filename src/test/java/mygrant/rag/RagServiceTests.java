package mygrant.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import mygrant.common.Confidence;
import mygrant.ai.OpenAiGenerationService;
import mygrant.rag.dto.RagQueryRequest;

@ExtendWith(MockitoExtension.class)
class RagServiceTests {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private OpenAiGenerationService generator;
    @Mock
    private mygrant.impact.ImpactJudgeService judge;

    private RagService ragService;

    @BeforeEach
    void setUp() {
        ragService = new RagService(vectorStore, generator, new EvidenceRanker(), judge,
                org.mockito.Mockito.mock(ProfilePolicyRetrieval.class),org.mockito.Mockito.mock(mygrant.policies.PolicyQuestionHistory.class));
    }

    @Test
    void requestsReviewWhenNoEvidenceIsFound() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        var response = ragService.query(new RagQueryRequest("How does the rule affect me?", "F-1"));

        assertThat(response.confidence()).isEqualTo(Confidence.LOW);
        assertThat(response.requiresReview()).isTrue();
        assertThat(response.citations()).isEmpty();
        verify(generator, never()).generate(any(), any());
    }

    @Test
    void rejectsUnsupportedVisaTypesBeforeSearching() {
        assertThatThrownBy(() -> ragService.query(new RagQueryRequest("Question", "B-2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported visa type");

        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void differentChunksFromOneSourceKeepSeparateCitationsAndPromptNumbers() {
        var metadata = java.util.Map.<String,Object>of("externalId","same-policy","title","Policy","agency","DHS",
                "status","FINAL","sourceType","PRIMARY","publicationDate","2026-01-01","sourceUrl","https://example.org");
        var first = org.springframework.ai.document.Document.builder().id("chunk-A").text("First passage").metadata(metadata).build();
        var second = org.springframework.ai.document.Document.builder().id("chunk-B").text("Second passage").metadata(metadata).build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(first, second, first));
        when(generator.generate(any(), any())).thenReturn("First claim [1]. Second claim [2].");
        when(judge.evaluateAnswer(any(), any(), anyList())).thenReturn(new mygrant.impact.JudgeEvaluation(
                mygrant.impact.JudgeEvaluation.Verdict.YES, "Supported", "judge"));

        var response = ragService.query(new RagQueryRequest("What changed?", "F-1"));

        assertThat(response.citations()).extracting(mygrant.rag.dto.CitationResponse::chunkId)
                .containsExactly("chunk-A", "chunk-B");
        assertThat(response.citations()).extracting(mygrant.rag.dto.CitationResponse::externalId)
                .containsExactly("same-policy", "same-policy");
        assertThat(response.retrievedEvidence()).extracting(mygrant.rag.dto.RetrievedEvidenceResponse::citationNumber)
                .containsExactly(1, 2);
        assertThat(response.retrievedEvidence()).extracting(mygrant.rag.dto.RetrievedEvidenceResponse::chunkId)
                .containsExactly("chunk-A", "chunk-B");
        verify(generator).generate(any(), org.mockito.ArgumentMatchers.argThat(context ->
                context.contains("[1] Chunk ID: chunk-A") && context.contains("[2] Chunk ID: chunk-B")));
        verify(judge).evaluateAnswer(any(), any(), org.mockito.ArgumentMatchers.eq(response.citations()));
    }

    @Test
    void generatedAnswerMustPassJudgeBeforeBeingReturned() {
        var document=org.springframework.ai.document.Document.builder().text("A proposed policy source")
                .metadata(java.util.Map.of("externalId","doc","title","Policy","agency","DHS","status","PROPOSED",
                        "sourceType","PRIMARY","publicationDate","2026-01-01","sourceUrl","https://example.org")).build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));
        when(generator.generate(any(),any())).thenReturn("Generated candidate [1]");
        for(var verdict:mygrant.impact.JudgeEvaluation.Verdict.values()){
            when(judge.evaluateAnswer(any(),any(),anyList())).thenReturn(new mygrant.impact.JudgeEvaluation(verdict,"Review","judge"));
            var response=ragService.query(new RagQueryRequest("When?","F-1"));
            assertThat(response.evaluation().verdict()).isEqualTo(verdict);
            if(verdict==mygrant.impact.JudgeEvaluation.Verdict.YES)assertThat(response.answer()).isEqualTo("Generated candidate [1]");
            else assertThat(response.answer()).doesNotContain("Generated candidate");
        }
    }
}
