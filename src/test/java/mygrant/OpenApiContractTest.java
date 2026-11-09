package mygrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import mygrant.auth.JwtService;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

/**
 * The API contract CI checks on every pull request: the generated OpenAPI document is valid,
 * every endpoint is under /api/v1 and fully documented, and the documented auth requirements
 * match what the security configuration does. Writes the document to target/openapi.json.
 * Uses the local PostgreSQL database; the test account rolls back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OpenApiContractTest {

    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch");
    private static final String ROLE_NOTE = "Requires the ";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @MockBean VectorStore vectors;

    private String spec;
    private List<Operation> operations;

    private record Operation(String method, String path, JsonNode node) {
        String name() {
            return method.toUpperCase() + " " + path;
        }

        boolean isPublic() {
            return node.has("security") && node.path("security").isEmpty();
        }

        boolean documentsRole() {
            return node.path("description").asText().contains(ROLE_NOTE);
        }
    }

    @BeforeEach
    void loadDocument() throws Exception {
        spec = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode docs = json.readTree(spec);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "openapi.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(docs));

        operations = new ArrayList<>();
        docs.path("paths").properties().forEach(path -> path.getValue().properties().stream()
                .filter(method -> METHODS.contains(method.getKey()))
                .forEach(method -> operations.add(new Operation(method.getKey(), path.getKey(), method.getValue()))));
    }

    @Test
    void documentIsValidOpenApi() {
        SwaggerParseResult result = new OpenAPIV3Parser().readContents(spec, null, new ParseOptions());

        assertThat(result.getMessages()).isEmpty();
        assertThat(result.getOpenAPI()).isNotNull();
        assertThat(result.getOpenAPI().getOpenapi()).startsWith("3.");
    }

    @Test
    void everyEndpointIsUnderApiV1() {
        // Swagger UI, /v3/api-docs and actuator are not part of the document.
        assertThat(operations).isNotEmpty()
                .allSatisfy(operation -> assertThat(operation.path()).as(operation.name()).startsWith("/api/v1/"));
    }

    @Test
    void everyOperationIsDocumented() throws Exception {
        JsonNode docs = json.readTree(spec);
        Set<String> sections = new HashSet<>();
        docs.path("tags").forEach(tag -> {
            assertThat(tag.path("description").asText()).as("description of tag " + tag.path("name")).isNotBlank();
            sections.add(tag.path("name").asText());
        });

        for (Operation operation : operations) {
            JsonNode node = operation.node();
            String name = operation.name();
            assertThat(node.path("summary").asText()).as(name + " summary").isNotBlank();
            assertThat(node.path("tags")).as(name + " tags").isNotEmpty()
                    .allSatisfy(tag -> assertThat(sections).as(name + " tag").contains(tag.asText()));

            List<String> codes = new ArrayList<>();
            node.path("responses").properties().forEach(response -> codes.add(response.getKey()));
            assertThat(codes).as(name + " success response").anyMatch(code -> code.matches("[23]\\d\\d"));
            for (Map.Entry<String, JsonNode> response : node.path("responses").properties()) {
                assertThat(response.getValue().path("description").asText())
                        .as(name + " " + response.getKey() + " description").isNotBlank();
                if (response.getKey().matches("[45]\\d\\d")) {
                    assertThat(response.getValue().at("/content/application~1json/schema/$ref").asText())
                            .as(name + " " + response.getKey() + " body").isEqualTo("#/components/schemas/ApiError");
                }
            }

            if (!operation.isPublic()) {
                assertThat(codes).as(name + " needs a token, so documents 401").contains("401");
            }
            if (operation.documentsRole()) {
                assertThat(codes).as(name + " needs a role, so documents 403").contains("403");
            }
            // SecurityConfig limits these paths to ADMIN whatever the controller says.
            if (operation.path().startsWith("/api/v1/admin/") || operation.path().startsWith("/api/v1/ingestion/")) {
                assertThat(node.path("description").asText()).as(name + " role").contains(ROLE_NOTE + "ADMIN role");
            }
        }
    }

    @Test
    void documentedAuthMatchesSecurity() throws Exception {
        String applicant = token();

        for (Operation operation : operations) {
            String name = operation.name();
            int signedOut = perform(operation, null);
            if (operation.isPublic()) {
                assertThat(signedOut).as(name + " is documented as public").isNotIn(401, 403);
            } else {
                assertThat(signedOut).as(name + " is documented as needing a token").isEqualTo(401);
            }
            if (operation.documentsRole()) {
                assertThat(perform(operation, applicant)).as(name + " is documented as role-restricted").isEqualTo(403);
            }
        }
    }

    /**
     * Sends a request that security rejects, or that fails validation on a public endpoint, so it
     * never changes data. The body satisfies only the staff-reply request, so method security, which
     * runs after validation, is what rejects it there.
     */
    private int perform(Operation operation, String authorization) throws Exception {
        String path = operation.path().replaceAll("\\{[^}]+}", "1");
        MockHttpServletRequestBuilder request = request(HttpMethod.valueOf(operation.method().toUpperCase()), path);
        if (!operation.method().equals("get") && !operation.method().equals("delete")) {
            request.contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"contract check\"}");
        }
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private String token() {
        User user = new User("Contract test", "contract-test-" + UUID.randomUUID() + "@example.com",
                "unused-test-password-hash", "F-1", null, null);
        user.setRole(UserRole.APPLICANT);
        users.saveAndFlush(user);
        return "Bearer " + jwt.generateAccessToken(user);
    }
}
