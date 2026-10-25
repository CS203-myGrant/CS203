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
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import mygrant.auth.JwtService;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

/**
 * Every endpoint that returns a list returns a Spring Page instead, in the order it always had.
 * Uses the local PostgreSQL database; test accounts and messages roll back after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ListEndpointsPaginationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;
    @MockBean VectorStore vectors;

    @Test
    void everyListEndpointReturnsAPage() throws Exception {
        String applicant = token(UserRole.APPLICANT);
        String admin = token(UserRole.ADMIN);
        long conversationId = json.readTree(mvc.perform(post("/api/v1/feedback/conversation")
                        .header("Authorization", applicant))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();

        String[][] lists = {
                {"/api/v1/policies", applicant},
                {"/api/v1/notifications", applicant},
                {"/api/v1/profiles/me/documents", applicant},
                {"/api/v1/feedback/messages", applicant},
                {"/api/v1/feedback/admin/conversations", admin},
                {"/api/v1/feedback/admin/conversations/" + conversationId + "/messages", admin},
                {"/api/v1/admin/users", admin},
                {"/api/v1/ingestion/status", admin},
                {"/api/v1/ingestion/visa-bulletin/entries/2026-10", admin}};
        for (String[] list : lists) {
            mvc.perform(get(list[0]).param("size", "1").header("Authorization", list[1]))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.size").value(1))
                    .andExpect(jsonPath("$.number").value(0))
                    .andExpect(jsonPath("$.totalElements").isNumber())
                    .andExpect(jsonPath("$.totalPages").isNumber())
                    .andExpect(jsonPath("$.first").value(true));
        }
    }

    @Test
    void pagesSplitAListInItsUsualOrder() throws Exception {
        String applicant = token(UserRole.APPLICANT);
        for (String message : new String[] {"one", "two", "three"}) {
            mvc.perform(post("/api/v1/feedback/messages").header("Authorization", applicant)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"" + message + "\"}"))
                    .andExpect(status().isOk());
        }

        mvc.perform(get("/api/v1/feedback/messages?size=2").header("Authorization", applicant))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].message").value("one"))
                .andExpect(jsonPath("$.content[1].message").value("two"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.last").value(false));
        mvc.perform(get("/api/v1/feedback/messages?size=2&page=1").header("Authorization", applicant))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].message").value("three"))
                .andExpect(jsonPath("$.last").value(true));

        // Connector health is built in memory, so it is paged after loading.
        String admin = token(UserRole.ADMIN);
        mvc.perform(get("/api/v1/ingestion/status?size=1&page=1").header("Authorization", admin))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.number").value(1));
    }

    @Test
    void unknownSortPropertyIsABadRequest() throws Exception {
        mvc.perform(get("/api/v1/policies?sort=noSuchField").header("Authorization", token(UserRole.APPLICANT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Cannot sort by noSuchField"));
    }

    private String token(UserRole role) {
        User user = new User("Paging test", "paging-test-" + UUID.randomUUID() + "@example.com",
                "unused-test-password-hash", "F-1", null, null);
        user.setRole(role);
        users.saveAndFlush(user);
        return "Bearer " + jwt.generateAccessToken(user);
    }
}
