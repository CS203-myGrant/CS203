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
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sendsClaudeSchemaAndReadsYesVerdict() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String answer = mapper.writeValueAsString(Map.of("verdict", "YES", "reason", "Evidence supports the steps"));
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
        String judgment=mapper.writeValueAsString(Map.of("verdict","NO","reason","Unsupported deadline"));
        String response=mapper.writeValueAsString(Map.of("stop_reason","end_turn","content",List.of(Map.of("type","text","text",judgment))));
        server.expect(requestTo("https://claude.test/v1/messages"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("policy Q&A answer")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("CURRENT QUESTION")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Unsupported candidate")))
                .andRespond(withSuccess(response,MediaType.APPLICATION_JSON));
        var judge=new ImpactJudgeService(builder,mapper,"key","judge","https://claude.test");
        assertThat(judge.evaluateAnswer("SOURCE [1]: facts. CURRENT QUESTION: When?","Unsupported candidate [1]").verdict())
                .isEqualTo(JudgeEvaluation.Verdict.NO);
        server.verify();
    }
}
