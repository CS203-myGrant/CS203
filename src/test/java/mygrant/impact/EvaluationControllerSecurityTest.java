package mygrant.impact;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.util.Optional;
import java.util.UUID;
import mygrant.auth.*;
import mygrant.user.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EvaluationController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, mygrant.common.RateLimiter.class})
class EvaluationControllerSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean OutputPublicationService service;
    @MockBean JwtService jwt;
    @MockBean UserRepository users;

    void token(UserRole role) {
        var user = new User(); user.setEmail("evaluate@example.org"); user.setRole(role);
        when(jwt.extractSubject("token")).thenReturn(user.getEmail());
        when(jwt.extractRole("token")).thenReturn(role.name());
        when(jwt.isAccessTokenValid("token", user.getEmail())).thenReturn(true);
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }

    @Test void anonymousIsUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/evaluate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outputId\":\""+UUID.randomUUID()+"\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void applicantsAndManagersCannotEvaluate() throws Exception {
        for (UserRole role : new UserRole[]{UserRole.APPLICANT, UserRole.MANAGER}) {
            token(role);
            mvc.perform(post("/api/v1/evaluate").header("Authorization", "Bearer token")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"outputId\":\""+UUID.randomUUID()+"\"}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test void adminCanEvaluateAndInputIsValidated() throws Exception {
        token(UserRole.ADMIN);
        UUID id = UUID.randomUUID();
        when(service.evaluate(id)).thenReturn(new OutputPublicationService.Result(id, null, "PENDING_REVIEW", false, 1L));
        mvc.perform(post("/api/v1/evaluate").header("Authorization", "Bearer token")
                .contentType(MediaType.APPLICATION_JSON).content("{\"outputId\":\""+id+"\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/evaluate").header("Authorization", "Bearer token")
                .contentType(MediaType.APPLICATION_JSON).content("{}")) .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/evaluate").header("Authorization", "Bearer token")
                .contentType(MediaType.APPLICATION_JSON).content("{\"outputId\":\"bad-id\"}")) .andExpect(status().isBadRequest());
        verify(service, times(1)).evaluate(any());
    }

    /** CS203-174: the evaluation is the endpoint's whole purpose, so an unavailable judge is a 503. */
    @Test void anUnavailableJudgeIsReportedAsTemporarilyUnavailable() throws Exception {
        token(UserRole.ADMIN);
        UUID id = UUID.randomUUID();
        when(service.evaluate(id)).thenReturn(new OutputPublicationService.Result(id,
                new JudgeEvaluation(JudgeEvaluation.Verdict.UNAVAILABLE, "Judge timed out", "judge"),
                "PENDING_REVIEW", false, 1L));
        mvc.perform(post("/api/v1/evaluate").header("Authorization", "Bearer token")
                .contentType(MediaType.APPLICATION_JSON).content("{\"outputId\":\""+id+"\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("Retry-After"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value(mygrant.common.AiUnavailableException.MESSAGE));
    }
}
