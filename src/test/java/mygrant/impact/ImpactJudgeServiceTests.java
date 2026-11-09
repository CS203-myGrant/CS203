package mygrant.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

class ImpactJudgeServiceTests {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void sendsClaudeSchemaAndReadsYesVerdict() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String answer = mapper.writeValueAsString(Map.of("verdict", "YES", "reason", "Evidence supports the steps", "faithfulness", 1.0, "relevance", 1.0));
        String response = mapper.writeValueAsString(Map.of("stop_reason", "end_turn", "content", List.of(Map.of("type", "text", "text", answer))));
        server.expect(requestTo("https://claude.test/v1/messages"))
                .andExpect(header("x-api-key", "key"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("claude-haiku-5-5")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("not inherently contradictory")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"output_config\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"effort\":\"low\"")))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        ImpactJudgeService judge = new ImpactJudgeService(builder, mapper, "key", "claude-haiku-5-5", "https://claude.test");
        JudgeEvaluation result = judge.evaluate("Policy and profile", candidate());

        assertThat(result.verdict()).isEqualTo(JudgeEvaluation.Verdict.YES);
        assertThat(result.reason()).contains("Evidence supports");
        server.verify();
    }

    @Test
    void providerFailureIsNotTreatedAsApproval() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://claude.test/v1/messages")).andRespond(withServerError());
        ImpactJudgeService judge = new ImpactJudgeService(builder, mapper, "key", "claude-haiku-5-5", "https://claude.test");

        assertThat(judge.evaluate("Policy and profile", candidate()).verdict())
                .isEqualTo(JudgeEvaluation.Verdict.UNAVAILABLE);
        server.verify();
    }

    @Test
    void generatesOneUnverifiedFallbackAssessmentWhenRequested() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String answer = "{\"urgency\":\"LOW\"}";
        String response = mapper.writeValueAsString(Map.of("stop_reason", "end_turn",
                "content", List.of(Map.of("type", "text", "text", answer))));
        server.expect(requestTo("https://claude.test/v1/messages"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("fallback assessment")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"effort\":\"low\"")))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        ImpactJudgeService judge = new ImpactJudgeService(builder, mapper, "key", "claude-haiku-5-5", "https://claude.test");

        String result = judge.generateFallbackAssessment("system", "policy context", Map.of("type", "object"));

        assertThat(result).isEqualTo(answer);
        server.verify();
    }

    private ProfileImpactAssessment candidate() {
        return new ProfileImpactAssessment(ImpactUrgency.MEDIUM, 70, "May apply",
                List.of("Relevant profile"), List.of("Review the source"), List.of(), true,
                "OpenAI", "generator", null, 1);
    }

    @Test
    void independentlyReviewsQuestionAnswersAndReadsRejection() throws Exception {
        RestClient.Builder builder=RestClient.builder();
        MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        String judgment=mapper.writeValueAsString(Map.of("verdict","NO","reason","Unsupported deadline","faithfulness",0.0,"relevance",1.0));
        String response=mapper.writeValueAsString(Map.of("stop_reason","end_turn","content",List.of(Map.of("type","text","text",judgment))));
        server.expect(requestTo("https://claude.test/v1/messages"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("policy Q&A answer")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("CURRENT QUESTION")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Unsupported candidate")))
                .andRespond(withSuccess(response,MediaType.APPLICATION_JSON));
        var judge=new ImpactJudgeService(builder,mapper,"key","judge","https://claude.test");
        assertThat(judge.evaluateAnswer("SOURCE [1]: facts. CURRENT QUESTION: When?","Unsupported candidate [1]", citations()).verdict())
                .isEqualTo(JudgeEvaluation.Verdict.NO);
        server.verify();
    }
    private List<mygrant.rag.dto.CitationResponse> citations() {
        return List.of(new mygrant.rag.dto.CitationResponse("actual-policy", "Policy", "DHS", "FINAL",
                "PRIMARY", "2026-01-01", null, "https://example.org/policy", 0.9, "chunk-A"));
    }

    private String response(double faithfulness, double relevance, String verdict) throws Exception {
        return mapper.writeValueAsString(Map.of("stop_reason", "end_turn", "content", List.of(Map.of(
                "type", "text", "text", mapper.writeValueAsString(Map.of("verdict", verdict,
                "reason", "Mock review", "faithfulness", faithfulness, "relevance", relevance))))));
    }

    @Test
    void sourceOnlyLegacyCitationCannotEstablishChunkMembership() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var judge = new ImpactJudgeService(builder, mapper, "key", "judge", "https://claude.test");
        var sourceOnly = List.of(new mygrant.rag.dto.CitationResponse("actual-policy", "Policy", "DHS", "FINAL",
                "PRIMARY", "2026-01-01", null, "https://example.org/policy", 0.9));
        var result = judge.evaluateAnswer("[1] Old source-level evidence", "Claim [1]", sourceOnly);
        assertThat(result.verdict()).isEqualTo(JudgeEvaluation.Verdict.NO);
        assertThat(result.citationsValid()).isFalse();
        server.verify();
    }

    @Test
    void secondRetrievedChunkOfSameSourcePassesButUnretrievedThirdChunkFails() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://claude.test/v1/messages"))
                .andRespond(withSuccess(response(1, 1, "YES"), MediaType.APPLICATION_JSON));
        var judge = new ImpactJudgeService(builder, mapper, "key", "judge", "https://claude.test");
        var chunks = List.of(citations().getFirst(), new mygrant.rag.dto.CitationResponse(
                "actual-policy", "Policy", "DHS", "FINAL", "PRIMARY", "2026-01-01", null,
                "https://example.org/policy", 0.9, "chunk-B"));
        var supported = judge.evaluateAnswer("[1] Chunk ID: chunk-A: First fact.\n[2] Chunk ID: chunk-B: Second fact.",
                "Second fact [2]", chunks);
        assertThat(supported.verdict()).isEqualTo(JudgeEvaluation.Verdict.YES);
        assertThat(supported.citationsValid()).isTrue();
        var unseen = judge.evaluateAnswer("Question mentions chunk-C [3], but supplied chunks are [1] and [2].",
                "Fact from unseen chunk [3]", chunks);
        assertThat(unseen.verdict()).isEqualTo(JudgeEvaluation.Verdict.NO);
        assertThat(unseen.citationsValid()).isFalse();
        server.verify();
    }

    @Test
    void groundedAnswerIsScoredVersionedAndPersisted() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://claude.test/v1/messages"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("grounded-output-v2-chunks")))
                .andRespond(withSuccess(response(1, 0.9, "YES"), MediaType.APPLICATION_JSON));
        var judge = new ImpactJudgeService(builder, mapper, "key", "judge", "https://claude.test");
        var store = org.mockito.Mockito.mock(EvaluationStore.class);
        judge.setEvaluationStore(store);
        String candidate = "Students must check the rule [1].";
        var result = judge.evaluateAnswer("Rule: students must check the rule.", candidate, citations());
        assertThat(result.verdict()).isEqualTo(JudgeEvaluation.Verdict.YES);
        assertThat(result.faithfulness()).isEqualTo(1);
        assertThat(result.relevance()).isEqualTo(0.9);
        assertThat(result.citationsValid()).isTrue();
        assertThat(result.evaluationId()).isNotNull();
        assertThat(result.evaluatedAt()).isNotNull();
        String expectedHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsBytes(candidate)));
        assertThat(result.outputVersion()).isEqualTo(expectedHash);
        org.mockito.Mockito.verify(store).save("ANSWER", result);
        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0.2,1,YES", "1,0.2,YES", "1,1,NO"})
    void unsupportedIrrelevantOrRejectedAnswersCannotPass(double faithfulness, double relevance, String verdict) throws Exception {
        RestClient.Builder builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://claude.test/v1/messages"))
                .andRespond(withSuccess(response(faithfulness, relevance, verdict), MediaType.APPLICATION_JSON));
        var judge = new ImpactJudgeService(builder, mapper, "key", "judge", "https://claude.test");
        var result = judge.evaluateAnswer("No deadline is specified.", "You must file tomorrow [1].", citations());
        assertThat(result.verdict()).isEqualTo(JudgeEvaluation.Verdict.NO);
        assertThat(result.faithfulness()).isEqualTo(faithfulness);
        server.verify();
    }

    @Test
    void invalidCitationsAreRejectedWithoutCallingModelEvenIfQuestionContainsThatNumber() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var judge = new ImpactJudgeService(builder, mapper, "key", "judge", "https://claude.test");
        var store = org.mockito.Mockito.mock(EvaluationStore.class);
        judge.setEvaluationStore(store);
        for (String candidate : List.of("Claim [2]", "Claim [0]", "Claim [999999999999999999999]")) {
            var result = judge.evaluateAnswer("Question says [2]; actual evidence is [1].", candidate, citations());
            assertThat(result.verdict()).isEqualTo(JudgeEvaluation.Verdict.NO);
            assertThat(result.citationsValid()).isFalse();
            assertThat(result.faithfulness()).isNull();
            org.mockito.Mockito.verify(store).save("ANSWER", result);
        }
        server.verify();
    }

    @Test
    void malformedScoresAndDisabledJudgeAreUnavailableAndPersisted() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://claude.test/v1/messages"))
                .andRespond(withSuccess(response(1.1, 1, "YES"), MediaType.APPLICATION_JSON));
        var judge = new ImpactJudgeService(builder, mapper, "key", "judge", "https://claude.test");
        var result = judge.evaluateAnswer("Evidence", "Claim [1]", citations());
        assertThat(result.verdict()).isEqualTo(JudgeEvaluation.Verdict.UNAVAILABLE);
        assertThat(result.faithfulness()).isNull();
        var disabled = new ImpactJudgeService(RestClient.builder(), mapper, "", "judge", "https://claude.test");
        var store = org.mockito.Mockito.mock(EvaluationStore.class);
        disabled.setEvaluationStore(store);
        var unavailable = disabled.evaluate("Evidence", candidate());
        assertThat(unavailable.verdict()).isEqualTo(JudgeEvaluation.Verdict.UNAVAILABLE);
        org.mockito.Mockito.verify(store).save("ASSESSMENT", unavailable);
        server.verify();
    }

    @Test
    void evaluationPersistenceFailureCannotReturnApproval() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://claude.test/v1/messages"))
                .andRespond(withSuccess(response(1, 1, "YES"), MediaType.APPLICATION_JSON));
        var judge = new ImpactJudgeService(builder, mapper, "key", "judge", "https://claude.test");
        var store = org.mockito.Mockito.mock(EvaluationStore.class);
        judge.setEvaluationStore(store);
        org.mockito.Mockito.doThrow(new IllegalStateException("Database unavailable"))
                .when(store).save(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> judge.evaluate("Evidence", candidate()))
                .isInstanceOf(IllegalStateException.class);
        server.verify();
    }

}
