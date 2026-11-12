package mygrant.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

import mygrant.common.AiUnavailableException;

/** Calls GPT-6.1 Sol for grounded answers and structured policy assessments. */
@Service
public class OpenAiGenerationService {
    private final RestClient client;
    private final AiCallRetry retry;
    private final String apiKey;
    private final String model;

    /** CS203-174: with the configured AI timeouts and bounded retry. */
    @Autowired
    public OpenAiGenerationService(RestClient.Builder builder, AiResilience resilience,
            @Value("${openai.api-key:}") String apiKey,
            @Value("${openai.model:gpt-6.1-sol}") String model,
            @Value("${openai.base-url:https://api.openai.com/v1}") String baseUrl) {
        this(resilience.withTimeouts(builder), resilience.retry(), apiKey, model, baseUrl);
    }

    /** One attempt on the given builder as it is, for tests that supply their own HTTP client. */
    public OpenAiGenerationService(RestClient.Builder builder, String apiKey, String model, String baseUrl) {
        this(builder, AiCallRetry.once(), apiKey, model, baseUrl);
    }

    public OpenAiGenerationService(RestClient.Builder builder, AiCallRetry retry, String apiKey, String model,
            String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
        this.retry = retry;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
    }

    public String generate(String system, String user) { return call(system, user, null); }

    public String generateJson(String system, String user, Map<String, Object> schema, String schemaName) {
        return call(system, user, Map.of("type", "json_schema", "json_schema", Map.of(
                "name", schemaName, "strict", true, "schema", schema)));
    }

    private String call(String system, String user, Map<String, Object> responseFormat) {
        if (apiKey.isBlank()) throw new AiUnavailableException();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("reasoning_effort", "low");
        request.put("messages", List.of(Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)));
        if (responseFormat != null) request.put("response_format", responseFormat);
        JsonNode response = retry.call("OpenAI chat completion", () -> client.post().uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(JsonNode.class));
        String content = response == null ? "" : response.path("choices").path(0)
                .path("message").path("content").asText();
        if (content.isBlank()) throw new AiUnavailableException();
        return content;
    }

    public String model() { return model; }
}
