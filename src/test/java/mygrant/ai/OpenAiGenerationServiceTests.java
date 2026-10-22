package mygrant.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Verifies the OpenAI client request shape using a local mock server only. */
class OpenAiGenerationServiceTests {

    @Test
    void sendsStructuredAssessmentToOpenAiAndReadsItsContent() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String response = new ObjectMapper().writeValueAsString(Map.of("choices",
                List.of(Map.of("message", Map.of("content", "{\"urgency\":\"LOW\"}")))));
        server.expect(requestTo("https://openai.test/chat/completions"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("gpt-6.1-sol")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("json_schema")))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        OpenAiGenerationService service = new OpenAiGenerationService(builder, "test-key",
                "gpt-6.1-sol", "https://openai.test");

        String result = service.generateJson("system", "user", Map.of("type", "object"), "assessment");

        assertThat(result).isEqualTo("{\"urgency\":\"LOW\"}");
        server.verify();
    }
}
