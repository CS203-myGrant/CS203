package mygrant.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import mygrant.auth.JwtService;
import mygrant.user.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;

/** Uses the local PostgreSQL database; test accounts and messages roll back after each test. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SupportIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;
    @MockBean VectorStore vectors;

    @Test
    void applicantMessageReachesStaffInboxAndReplyReachesApplicant() throws Exception {
        String applicant = account(UserRole.APPLICANT);
        String admin = account(UserRole.ADMIN);
        String manager = account(UserRole.MANAGER);
        String stranger = account(UserRole.APPLICANT);
        String sent = mvc.perform(post("/api/v1/feedback/messages")
                .header("Authorization", applicant).contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Support delivery test\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long messageId = json.readTree(sent).get("id").asLong();
        String inbox = mvc.perform(get("/api/v1/feedback/admin/conversations")
                .header("Authorization", admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long conversationId = -1;
        for (var conversation : json.readTree(inbox)) {
            String messages = mvc.perform(get("/api/v1/feedback/admin/conversations/" + conversation.get("id").asLong() + "/messages")
                    .header("Authorization", manager)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            for (var message : json.readTree(messages)) {
                if (message.get("id").asLong() == messageId) {
                    assertThat(message.get("message").asText()).isEqualTo("Support delivery test");
                    conversationId = conversation.get("id").asLong();
                }
            }
        }
        assertThat(conversationId).isPositive();
        mvc.perform(post("/api/v1/feedback/admin/conversations/" + conversationId + "/messages")
                .header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"We received your message\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/feedback/messages").header("Authorization", applicant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].message").value("We received your message"))
                .andExpect(jsonPath("$[1].senderType").value("STAFF"));
        mvc.perform(get("/api/v1/feedback/admin/conversations/" + conversationId + "/messages")
                .header("Authorization", stranger)).andExpect(status().isForbidden());
    }

    @Test
    void unavailableAiReturnsServiceErrorAndSameTokenStillWorks() throws Exception {
        String applicant = account(UserRole.APPLICANT);
        when(vectors.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenThrow(new ResourceAccessException("Ollama unavailable"));
        mvc.perform(post("/api/v1/rag/query").header("Authorization", applicant)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"What changed?\",\"visaType\":\"F-1\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Talk to a person")));
        mvc.perform(post("/api/v1/feedback/conversation").header("Authorization", applicant))
                .andExpect(status().isOk());
    }

    @Test
    void unauthenticatedRequestsReturn401() throws Exception {
        mvc.perform(get("/api/v1/feedback/messages")).andExpect(status().isUnauthorized());
    }

    private String account(UserRole role) {
        User user = new User("Support test", "support-test-" + UUID.randomUUID() + "@example.com",
                "unused-test-password-hash", "F-1", null, null);
        user.setRole(role);
        users.saveAndFlush(user);
        return "Bearer " + jwt.generateAccessToken(user);
    }
}
