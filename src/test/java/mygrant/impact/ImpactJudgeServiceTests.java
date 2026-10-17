package mygrant.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
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
    void sendsQwenStrictSchemaAndReadsYesVerdict() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String answer = mapper.writeValueAsString(Map.of("verdict", "YES", "reason", "Evidence supports the steps"));
        String response = mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", answer)))));
        server.expect(requestTo("https://groq.test/chat/completions"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("qwen/qwen3.8-27b")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("not inherently contradictory")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"strict\":true")))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        ImpactJudgeService judge = new ImpactJudgeService(builder, mapper, "key", "qwen/qwen3.8-27b", "https://groq.test");
        JudgeEvaluation result = judge.evaluate("Policy and profile", candidate());

        assertThat(result.verdict()).isEqualTo(JudgeEvaluation.Verdict.YES);
        assertThat(result.reason()).contains("Evidence supports");
        server.verify();
    }

    @Test
    void providerFailureIsNotTreatedAsApproval() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://groq.test/chat/completions")).andRespond(withServerError());
        ImpactJudgeService judge = new ImpactJudgeService(builder, mapper, "key", "qwen-test", "https://groq.test");

        assertThat(judge.evaluate("Policy and profile", candidate()).verdict())
                .isEqualTo(JudgeEvaluation.Verdict.UNAVAILABLE);
        server.verify();
    }

    private ProfileImpactAssessment candidate() {
        return new ProfileImpactAssessment(ImpactUrgency.MEDIUM, 70, "May apply",
                List.of("Relevant profile"), List.of("Review the source"), true,
                "Groq", "generator", null, 1);
    }
}
