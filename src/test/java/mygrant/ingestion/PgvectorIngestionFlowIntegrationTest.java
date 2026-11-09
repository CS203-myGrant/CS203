package mygrant.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.testcontainers.junit.jupiter.Testcontainers;

import mygrant.ai.OpenAiGenerationService;
import mygrant.common.Confidence;
import mygrant.impact.ImpactJudgeService;
import mygrant.impact.JudgeEvaluation;
import mygrant.ingestion.dto.IngestPolicyRequest;
import mygrant.notifications.NotificationRepository;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;
import mygrant.rag.RagService;
import mygrant.rag.dto.RagQueryResponse;
import mygrant.support.PgvectorIntegrationTestBase;
import mygrant.user.User;
import mygrant.user.UserProfile;
import mygrant.user.UserProfileRepository;
import mygrant.user.UserRepository;

/** Exercises Flyway, pgvector ingestion, post-commit notification and RAG retrieval together. */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PgvectorIngestionFlowIntegrationTest.DeterministicEmbeddingConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class PgvectorIngestionFlowIntegrationTest extends PgvectorIntegrationTestBase {

    @Autowired private PolicyIngestionService ingestion;
    @Autowired private UserRepository users;
    @Autowired private UserProfileRepository profiles;
    @Autowired private NotificationRepository notifications;
    @Autowired private RagService rag;

    @MockBean private OpenAiGenerationService generator;
    @MockBean private ImpactJudgeService judge;

    @Test
    void ingestionCreatesNotificationAndMakesThePolicyRetrievable() {
        User applicant = users.saveAndFlush(new User("Integration applicant",
                "integration-" + UUID.randomUUID() + "@example.org", "unused"));
        profiles.saveAndFlush(UserProfile.registrationProfile(applicant.getId(), "F-1", null, null, null, null));

        String externalId = "integration-" + UUID.randomUUID();
        var response = ingestion.ingest(new IngestPolicyRequest(
                externalId,
                "F-1 practical training reporting update",
                "DHS",
                "F-1",
                PolicyStatus.FINAL,
                SourceType.PRIMARY,
                LocalDate.of(2026, 11, 9),
                LocalDate.of(2026, 12, 1),
                "https://example.org/" + externalId,
                "F-1 students must report practical training changes to their designated school official.",
                null, null, null));

        assertThat(notifications.findByUserIdOrderByCreatedAtDesc(applicant.getId(),
                org.springframework.data.domain.Pageable.unpaged()))
                .anySatisfy(notification -> assertThat(notification.getPolicyId()).isEqualTo(response.policyId()));

        when(generator.generate(any(), any())).thenReturn("Report the practical training change [1].");
        when(judge.evaluateAnswer(any(), any(), any())).thenReturn(
                new JudgeEvaluation(JudgeEvaluation.Verdict.YES, "Supported by the indexed policy.", "test"));

        RagQueryResponse answer = rag.queryForUser(applicant.getEmail(), "What must I report about practical training?");

        assertThat(answer.answer()).isEqualTo("Report the practical training change [1].");
        assertThat(answer.confidence()).isNotEqualTo(Confidence.LOW);
        assertThat(answer.citations()).singleElement().satisfies(citation ->
                assertThat(citation.externalId()).isEqualTo(externalId));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DeterministicEmbeddingConfiguration {
        @Bean
        @Primary
        EmbeddingModel deterministicEmbeddingModel() {
            return new EmbeddingModel() {
                @Override
                public EmbeddingResponse call(EmbeddingRequest request) {
                    List<Embedding> embeddings = java.util.stream.IntStream.range(0, request.getInstructions().size())
                            .mapToObj(index -> new Embedding(vector(request.getInstructions().get(index)), index))
                            .toList();
                    return new EmbeddingResponse(embeddings);
                }

                @Override
                public float[] embed(Document document) {
                    return vector(document.getText());
                }

                @Override
                public int dimensions() {
                    return 1024;
                }
            };
        }

        private static float[] vector(String text) {
            float[] vector = new float[1024];
            // The test checks persistence/filtering/retrieval rather than semantic embedding
            // quality. Identical unit vectors guarantee a deterministic match above the RAG
            // similarity threshold without contacting an embedding provider.
            Arrays.fill(vector, 1.0f);
            return vector;
        }
    }
}
