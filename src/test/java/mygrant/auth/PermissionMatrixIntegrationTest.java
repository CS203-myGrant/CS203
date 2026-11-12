package mygrant.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import mygrant.ai.OpenAiGenerationService;
import mygrant.impact.ImpactJudgeService;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

/**
 * Keeps SecurityConfig honest as controllers are added: every protected API mapping is checked
 * anonymously and against each role that its path rule must reject. The external AI/vector
 * clients are mocked because this class tests only the HTTP security boundary.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PermissionMatrixIntegrationTest {

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh",
            "/api/v1/auth/workos/login", "/api/v1/auth/workos/callback",
            "/api/v1/webhooks/workos", "/api/v1/email/unsubscribe", "/api/v1/auth/verify-email");

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping mappings;

    @MockBean private VectorStore vectorStore;
    @MockBean private OpenAiGenerationService generator;
    @MockBean private ImpactJudgeService judge;

    @Test
    void everyProtectedEndpointRejectsAnonymousAndForbiddenRoles() throws Exception {
        List<Endpoint> endpoints = protectedEndpoints();
        assertThat(endpoints).isNotEmpty();

        for (Endpoint endpoint : endpoints) {
            mvc.perform(request(endpoint.method(), endpoint.path()))
                    .andExpect(status().isUnauthorized());

            for (UserRole role : UserRole.values()) {
                if (!allowedRoles(endpoint.path()).contains(role)) {
                    mvc.perform(request(endpoint.method(), endpoint.path())
                                    .header("Authorization", token(role)))
                            .andExpect(status().isForbidden());
                }
            }
        }
    }

    @Test
    void eachAuthenticatedRoleCanReachItsRepresentativeEndpoint() throws Exception {
        mvc.perform(request(HttpMethod.GET, "/api/v1/notifications")
                        .header("Authorization", token(UserRole.APPLICANT)))
                .andExpect(status().isOk());
        mvc.perform(request(HttpMethod.GET, "/api/v1/review/impacts")
                        .header("Authorization", token(UserRole.MANAGER)))
                .andExpect(status().isOk());
        mvc.perform(request(HttpMethod.GET, "/api/v1/anomalies")
                        .header("Authorization", token(UserRole.ADMIN)))
                .andExpect(status().isOk());
    }

    private List<Endpoint> protectedEndpoints() {
        Set<Endpoint> endpoints = new LinkedHashSet<>();
        mappings.getHandlerMethods().forEach((mapping, handler) -> {
            for (String pattern : mapping.getPatternValues()) {
                String path = pattern.replaceAll("\\{[^/]+}", "1");
                if (!path.startsWith("/api/v1/") || PUBLIC_PATHS.contains(path)) {
                    continue;
                }
                Set<RequestMethod> methods = mapping.getMethodsCondition().getMethods();
                if (methods.isEmpty()) {
                    endpoints.add(new Endpoint(HttpMethod.GET, path));
                } else {
                    methods.forEach(method -> endpoints.add(new Endpoint(HttpMethod.valueOf(method.name()), path)));
                }
            }
        });
        return endpoints.stream().sorted((left, right) -> (left.method() + left.path())
                .compareTo(right.method() + right.path())).toList();
    }

    private EnumSet<UserRole> allowedRoles(String path) {
        if (path.startsWith("/api/v1/ingestion/") || path.startsWith("/api/v1/admin/")
                || path.startsWith("/api/v1/anomalies/")
                || path.matches("/api/v1/policies/[^/]+/dispute/clear")) {
            return EnumSet.of(UserRole.ADMIN);
        }
        if (path.startsWith("/api/v1/manager/")) {
            return EnumSet.of(UserRole.MANAGER);
        }
        if (path.startsWith("/api/v1/review/")) {
            return EnumSet.of(UserRole.MANAGER, UserRole.ADMIN);
        }
        return EnumSet.allOf(UserRole.class);
    }

    private String token(UserRole role) {
        String email = "permission-" + role.name().toLowerCase() + "@example.org";
        User user = users.findByEmail(email).orElseGet(() -> {
            User created = new User("Permission " + role, email, "unused");
            created.setRole(role);
            return users.saveAndFlush(created);
        });
        return "Bearer " + jwt.generateAccessToken(user);
    }

    private record Endpoint(HttpMethod method, String path) { }
}
