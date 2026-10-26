package mygrant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import mygrant.auth.JwtService;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

/**
 * Policies, ingestion and RAG moved under /api/v1 with no aliases left behind, and kept their
 * security rules. Uses the local PostgreSQL database; test accounts roll back after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApiRoutesTest {

    private static final String RAG_QUESTION = "{\"question\":\"What changed?\",\"visaType\":\"F-1\"}";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @MockBean VectorStore vectors;

    @Test
    void movedRoutesAnswerUnderApiV1() throws Exception {
        String applicant = token(UserRole.APPLICANT);
        String admin = token(UserRole.ADMIN);

        mvc.perform(get("/api/v1/policies").header("Authorization", applicant))
                .andExpect(status().isOk());
        // A 404 from the controller, not from routing: the message comes from PolicyNotFoundException.
        mvc.perform(get("/api/v1/policies/{id}", Long.MAX_VALUE).header("Authorization", applicant))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Policy document " + Long.MAX_VALUE + " was not found"));
        mvc.perform(post("/api/v1/rag/query").header("Authorization", applicant)
                        .contentType(MediaType.APPLICATION_JSON).content(RAG_QUESTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").isNotEmpty());
        mvc.perform(get("/api/v1/ingestion/status").header("Authorization", admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/ingestion/federal-register/health").header("Authorization", admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/ingestion/visa-bulletin/health").header("Authorization", admin))
                .andExpect(status().isOk());
    }

    @Test
    void movedRoutesKeepTheirSecurityRules() throws Exception {
        String applicant = token(UserRole.APPLICANT);
        String manager = token(UserRole.MANAGER);

        mvc.perform(get("/api/v1/policies")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/rag/query").contentType(MediaType.APPLICATION_JSON).content(RAG_QUESTION))
                .andExpect(status().isUnauthorized());

        for (MockHttpServletRequestBuilder ingestion : new MockHttpServletRequestBuilder[] {
                post("/api/v1/ingestion/documents").contentType(MediaType.APPLICATION_JSON).content("{}"),
                post("/api/v1/ingestion/federal-register/2026-14439"),
                get("/api/v1/ingestion/federal-register/health"),
                get("/api/v1/ingestion/status"),
                post("/api/v1/ingestion/visa-bulletin/2026-10"),
                get("/api/v1/ingestion/visa-bulletin/entries/2026-10"),
                post("/api/v1/ingestion/news-rss/pull"),
                get("/api/v1/ingestion/uscis-processing-times/health")}) {
            mvc.perform(ingestion).andExpect(status().isUnauthorized());
            mvc.perform(ingestion.header("Authorization", applicant)).andExpect(status().isForbidden());
            mvc.perform(ingestion.header("Authorization", manager)).andExpect(status().isForbidden());
        }
    }

    @Test
    void unversionedRoutesAreGone() throws Exception {
        // An admin token, so a missing route cannot be mistaken for a security rejection.
        String admin = token(UserRole.ADMIN);

        for (String path : new String[] {"/policies", "/policies/1", "/ingestion/federal-register/health",
                "/ingestion/status", "/ingestion/visa-bulletin/health"}) {
            mvc.perform(get(path).header("Authorization", admin)).andExpect(status().isNotFound());
        }
        for (String path : new String[] {"/rag/query", "/ingestion/documents",
                "/ingestion/federal-register/2026-14439", "/ingestion/visa-bulletin/2026-10"}) {
            mvc.perform(post(path).header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON).content(RAG_QUESTION))
                    .andExpect(status().isNotFound());
        }
    }

    private String token(UserRole role) {
        User user = new User("Routes test", "routes-test-" + UUID.randomUUID() + "@example.com",
                "unused-test-password-hash", "F-1", null, null);
        user.setRole(role);
        user.setProfileComplete(true);
        users.saveAndFlush(user);
        return "Bearer " + jwt.generateAccessToken(user);
    }
}
