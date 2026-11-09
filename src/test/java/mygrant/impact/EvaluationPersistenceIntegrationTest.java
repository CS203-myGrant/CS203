package mygrant.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import mygrant.rag.dto.CitationResponse;

@SpringBootTest
@Transactional
class EvaluationPersistenceIntegrationTest {
    @Autowired EvaluationStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @Test
    void persistsEachCandidateVersionAndPreservesEvaluationMetadataInJson() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        String judgment = mapper.writeValueAsString(Map.of("verdict", "YES", "reason", "Supported",
                "faithfulness", 1, "relevance", 0.9));
        String response = mapper.writeValueAsString(Map.of("stop_reason", "end_turn", "content",
                List.of(Map.of("type", "text", "text", judgment))));
        server.expect(requestTo("https://judge.test/v1/messages"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        var judge = new ImpactJudgeService(builder, mapper, "test", "mock-judge", "https://judge.test");
        judge.setEvaluationStore(store);
        var citations = List.of(new CitationResponse("source", "Title", "DHS", "FINAL", "PRIMARY",
                "2026-01-01", null, "https://example.org", 1.0, "chunk-A"));
        var grounded = judge.evaluateAnswer("Source [1]: Rule applies.", "Rule applies [1].", citations);
        var invalid = judge.evaluateAnswer("Source [1]: Rule applies.", "Rule applies [2].", citations);
        for (var evaluation : List.of(grounded, invalid)) {
            var row = jdbc.queryForMap("SELECT * FROM ai_output_evaluations WHERE id = ?", evaluation.evaluationId());
            assertThat(row.get("output_version")).isEqualTo(evaluation.outputVersion());
            assertThat(row.get("verdict")).isEqualTo(evaluation.verdict().name());
            assertThat(row.get("faithfulness")).isEqualTo(evaluation.faithfulness());
            assertThat(row.get("relevance")).isEqualTo(evaluation.relevance());
            assertThat(row.get("citations_valid")).isEqualTo(evaluation.citationsValid());
            assertThat(row.get("evaluated_at")).isNotNull();
            assertThat(mapper.readValue(mapper.writeValueAsString(evaluation), JudgeEvaluation.class))
                    .isEqualTo(evaluation);
        }
        assertThat(grounded.outputVersion()).isNotEqualTo(invalid.outputVersion());
        server.verify();
    }
}
