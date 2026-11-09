package mygrant.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The JSON email endpoints are documented with schemas; the HTML unsubscribe page is not. */
@SpringBootTest
@AutoConfigureMockMvc
class EmailApiDocsTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean VectorStore vectors;

    @Test
    void emailPreferencesAreDocumentedAndUnsubscribePagesAreHidden() throws Exception {
        JsonNode docs = json.readTree(mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode preferences = docs.path("paths").path("/api/v1/profiles/me/email-preferences");

        assertThat(preferences.path("get").path("responses").path("200").path("content")
                .path("application/json").path("schema").path("$ref").asText()).endsWith("/EmailPreferencesResponse");
        assertThat(preferences.path("put").path("requestBody").path("content")
                .path("application/json").path("schema").path("$ref").asText()).endsWith("/EmailPreferencesRequest");
        assertThat(preferences.path("put").path("responses").has("400")).isTrue();

        JsonNode schemas = docs.path("components").path("schemas");
        assertThat(schemas.path("EmailPreferencesRequest").path("required"))
                .extracting(JsonNode::asText).contains("criticalDelivery", "otherDelivery");
        assertThat(schemas.path("EmailPreferencesRequest").path("properties").path("criticalDelivery")
                .path("enum")).extracting(JsonNode::asText).containsExactly("IMMEDIATE", "DIGEST", "OFF");
        assertThat(schemas.path("EmailPreferencesResponse").path("properties").path("ineligibleReason")
                .path("enum")).extracting(JsonNode::asText).contains("EMAIL_NOT_VERIFIED");

        JsonNode digestRun = docs.path("paths").path("/api/v1/admin/email/digest/run").path("post");
        assertThat(digestRun.path("responses").path("200").path("content").path("application/json").path("schema")
                .path("$ref").asText()).endsWith("/DigestRunResponse");
        assertThat(digestRun.path("parameters")).extracting(parameter -> parameter.path("name").asText())
                .containsExactly("date");
        assertThat(digestRun.path("responses").has("503")).isTrue();
        assertThat(schemas.path("DigestRunResponse").path("properties").has("digestsCreated")).isTrue();

        assertThat(docs.path("paths").has("/api/v1/notifications/{id}")).isTrue();
        assertThat(docs.path("paths").has("/api/v1/email/unsubscribe")).isFalse();
    }
}
