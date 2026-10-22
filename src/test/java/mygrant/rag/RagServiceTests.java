package mygrant.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    private RagService ragService;

    @BeforeEach
    void setUp() {
        ragService = new RagService(vectorStore, generator, new EvidenceRanker());
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
}
