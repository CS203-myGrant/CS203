package mygrant.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.web.client.ResourceAccessException;

import mygrant.auth.JwtAuthenticationFilter;
import mygrant.auth.JwtService;
import mygrant.auth.SecurityConfig;
import mygrant.feedback.FeedbackController;
import mygrant.feedback.FeedbackService;
import mygrant.ingestion.visabulletin.VisaBulletinController;
import mygrant.ingestion.visabulletin.VisaBulletinImporter;
import mygrant.ingestion.visabulletin.VisaBulletinStore;
import mygrant.policies.PolicyController;
import mygrant.policies.PolicyService;
import mygrant.rag.RagController;
import mygrant.rag.RagService;
import mygrant.user.ImmigrationDocumentController;
import mygrant.user.ImmigrationDocumentService;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

/**
 * Every kind of failure, from the security filters to Spring MVC to the controllers, answers
 * with the same ApiError JSON and never with a stack trace or an unexpected exception's message.
 */
@WebMvcTest({PolicyController.class, RagController.class, VisaBulletinController.class,
        FeedbackController.class, ImmigrationDocumentController.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ApiErrorResponsesTest {

    private static final String RAG_QUESTION = "{\"question\":\"What changed?\",\"visaType\":\"F-1\"}";

    @Autowired MockMvc mvc;
    @MockBean PolicyService policyService;
    @MockBean RagService ragService;
    @MockBean VisaBulletinImporter visaBulletinImporter;
    @MockBean VisaBulletinStore visaBulletinStore;
    @MockBean FeedbackService feedbackService;
    @MockBean ImmigrationDocumentService documentService;
    @MockBean JwtService jwtService;
    @MockBean UserRepository userRepository;

    @Test
    void securityRejectionsAreApiErrors() throws Exception {
        // The notification stream asks only for text/event-stream; the error is still JSON.
        mvc.perform(get("/api/v1/policies").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(apiError(401, "Please sign in to continue"));

        String applicant = token(UserRole.APPLICANT, true);
        mvc.perform(get("/api/v1/ingestion/visa-bulletin/health").header("Authorization", applicant))
                .andExpect(apiError(403, "You do not have permission to do this"));
        mvc.perform(get("/api/v1/feedback/admin/conversations").header("Authorization", applicant))
                .andExpect(apiError(403, "You do not have permission to do this"));

        mvc.perform(get("/api/v1/policies").header("Authorization", token(UserRole.APPLICANT, false)))
                .andExpect(apiError(403, "Complete your profile before using this feature"));
    }

    @Test
    void routingProblemsAreApiErrors() throws Exception {
        String applicant = token(UserRole.APPLICANT, true);

        mvc.perform(get("/api/v1/no-such-route").header("Authorization", applicant))
                .andExpect(apiError(404, "No endpoint GET /api/v1/no-such-route"));
        mvc.perform(delete("/api/v1/policies").header("Authorization", applicant))
                .andExpect(apiError(405, "Method 'DELETE' is not supported."))
                .andExpect(header().string("Allow", containsString("GET")));
        mvc.perform(post("/api/v1/rag/query").header("Authorization", applicant)
                        .contentType(MediaType.TEXT_PLAIN).content("What changed?"))
                .andExpect(apiError(415, "Content-Type 'text/plain;charset=UTF-8' is not supported."));
    }

    @Test
    void requestProblemsAreApiErrors() throws Exception {
        String applicant = token(UserRole.APPLICANT, true);

        mvc.perform(post("/api/v1/rag/query").header("Authorization", applicant)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(apiError(400, "Request validation failed"))
                .andExpect(jsonPath("$.validationErrors.question").exists())
                .andExpect(jsonPath("$.validationErrors.visaType").exists());
        mvc.perform(post("/api/v1/rag/query").header("Authorization", applicant)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":"))
                .andExpect(apiError(400, "Request body is invalid. Check the JSON and date values."));
        mvc.perform(get("/api/v1/policies/not-a-number").header("Authorization", applicant))
                .andExpect(apiError(400, "Invalid value for id"));
        mvc.perform(multipart("/api/v1/profiles/me/documents")
                        .file(new MockMultipartFile("file", "i20.pdf", "application/pdf", new byte[] {1}))
                        .header("Authorization", applicant))
                .andExpect(apiError(400, "Required parameter 'documentType' is not present."));
        mvc.perform(get("/api/v1/ingestion/visa-bulletin/entries/2026-13")
                        .header("Authorization", token(UserRole.ADMIN, true)))
                .andExpect(apiError(400, "month must be YYYY-MM"));
    }

    @Test
    void upstreamFailuresAreApiErrors() throws Exception {
        when(visaBulletinImporter.importMonth(any())).thenThrow(new IllegalStateException("parser broke"));
        mvc.perform(post("/api/v1/ingestion/visa-bulletin/2026-10").header("Authorization", token(UserRole.ADMIN, true)))
                .andExpect(apiError(502, "Visa Bulletin import failed; inspect /api/v1/ingestion/visa-bulletin/health"));

        when(ragService.query(any())).thenThrow(new ResourceAccessException("Embedding service unavailable"));
        mvc.perform(post("/api/v1/rag/query").header("Authorization", token(UserRole.APPLICANT, true))
                        .contentType(MediaType.APPLICATION_JSON).content(RAG_QUESTION))
                .andExpect(apiError(503,
                        "The AI service is unavailable. Please try again later or choose Talk to a person."));
    }

    @Test
    void unexpectedFailuresHideTheirDetails() throws Exception {
        when(ragService.query(any())).thenThrow(new IllegalStateException("password=hunter2"));

        mvc.perform(post("/api/v1/rag/query").header("Authorization", token(UserRole.APPLICANT, true))
                        .contentType(MediaType.APPLICATION_JSON).content(RAG_QUESTION))
                .andExpect(apiError(500, "Something went wrong. Please try again later."))
                .andExpect(content().string(not(containsString("hunter2"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    /** The ApiError shape, and nothing from Spring Boot's default error body. */
    private static ResultMatcher apiError(int status, String message) {
        return result -> {
            for (ResultMatcher matcher : List.of(
                    status().is(status),
                    content().contentType(MediaType.APPLICATION_JSON),
                    jsonPath("$.timestamp").isNotEmpty(),
                    jsonPath("$.status").value(status),
                    jsonPath("$.error").value(HttpStatus.valueOf(status).getReasonPhrase()),
                    jsonPath("$.message").value(message),
                    jsonPath("$.validationErrors").isMap(),
                    jsonPath("$.path").doesNotExist(),
                    jsonPath("$.trace").doesNotExist(),
                    jsonPath("$.exception").doesNotExist())) {
                matcher.match(result);
            }
        };
    }

    private String token(UserRole role, boolean profileComplete) {
        String token = role.name().toLowerCase() + (profileComplete ? "" : "-onboarding");
        User user = new User();
        user.setEmail(token + "@example.com");
        user.setRole(role);
        user.setProfileComplete(profileComplete);

        when(jwtService.extractSubject(token)).thenReturn(user.getEmail());
        when(jwtService.extractRole(token)).thenReturn(role.name());
        when(jwtService.isAccessTokenValid(token, user.getEmail())).thenReturn(true);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        return "Bearer " + token;
    }
}
