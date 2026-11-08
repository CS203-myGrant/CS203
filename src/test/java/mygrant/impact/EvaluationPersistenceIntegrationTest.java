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
    @Autowired EvaluationMetricsService metrics;

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
                "2026-01-01", null, "https://example.org", 1.0));
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
    @Test
    void metricsIncludeRejectedAndUnavailableAttemptsWithCorrectDenominatorsAndGroups() {
        var since = java.time.Instant.parse("2001-11-01T00:00:00Z");
        var until = since.plusSeconds(3600);
        saveMetric("ANSWER", JudgeEvaluation.Verdict.YES, 1.0, 1.0, true, since, "judge-a", "v1");
        saveMetric("ANSWER", JudgeEvaluation.Verdict.NO, 0.2, 1.0, true, since, "judge-a", "v1");
        saveMetric("ANSWER", JudgeEvaluation.Verdict.NO, null, null, false, since, "judge-a", "v1");
        saveMetric("ASSESSMENT", JudgeEvaluation.Verdict.UNAVAILABLE, null, null, null, since, "judge-b", "v2");
        saveMetric("ASSESSMENT", JudgeEvaluation.Verdict.YES, 0.8, 0.9, null, since, "judge-b", "v2");
        // The lower bound is included; the upper bound and earlier records are excluded.
        saveMetric("ANSWER", JudgeEvaluation.Verdict.NO, null, null, false, until, "judge-a", "v1");
        saveMetric("ANSWER", JudgeEvaluation.Verdict.NO, null, null, false, since.minusSeconds(1), "judge-a", "v1");
        var report = metrics.report(since, until);
        var overall = report.overall();
        assertThat(overall.total()).isEqualTo(5);
        assertThat(overall.approved()).isEqualTo(2);
        assertThat(overall.rejected()).isEqualTo(2);
        assertThat(overall.unavailable()).isEqualTo(1);
        assertThat(overall.scored()).isEqualTo(3);
        assertThat(overall.averageFaithfulness()).isCloseTo(2.0 / 3, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(overall.averageRelevance()).isCloseTo(2.9 / 3, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(overall.approvalRate()).isEqualTo(0.4);
        assertThat(overall.citationsChecked()).isEqualTo(3);
        assertThat(overall.invalidCitations()).isEqualTo(1);
        assertThat(report.groups()).hasSize(2);
        var answer = report.groups().stream().filter(g -> g.outputType().equals("ANSWER")).findFirst().orElseThrow();
        assertThat(answer.model()).isEqualTo("judge-a");
        assertThat(answer.promptVersion()).isEqualTo("v1");
        assertThat(answer.metrics().total()).isEqualTo(3);
    }

    @Test
    void emptyMetricsDoNotInventZeroQualityScoresAndInvalidWindowIsRejected() {
        var since = java.time.Instant.parse("2000-01-01T00:00:00Z");
        var report = metrics.report(since, since.plusSeconds(1));
        assertThat(report.overall().total()).isZero();
        assertThat(report.overall().averageFaithfulness()).isNull();
        assertThat(report.overall().averageRelevance()).isNull();
        assertThat(report.overall().approvalRate()).isNull();
        assertThat(report.groups()).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> metrics.report(since, since))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("400");
        var defaults = metrics.report(null, null);
        assertThat(java.time.Duration.between(defaults.since(), defaults.until()).toDays()).isEqualTo(30);
    }

    private void saveMetric(String type, JudgeEvaluation.Verdict verdict, Double faithful, Double relevant,
            Boolean citations, java.time.Instant time, String model, String prompt) {
        store.save(type, new JudgeEvaluation(verdict, "mock", model, faithful, relevant, citations,
                prompt, java.util.UUID.randomUUID(), "a".repeat(64), time));
    }

}
