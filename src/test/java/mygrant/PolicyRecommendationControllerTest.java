package mygrant;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import mygrant.impact.ProfileImpactAssessmentService;
import mygrant.policies.*;

class PolicyRecommendationControllerTest {
    @Test
    void recommendationRouteUsesAuthenticatedUserAndStoredPolicy() throws Exception {
        PolicyService policies = mock(PolicyService.class);
        ProfileImpactAssessmentService assessments = mock(ProfileImpactAssessmentService.class);
        PolicyRecommendationStore store = mock(PolicyRecommendationStore.class);
        when(store.getOrGenerate(anyString(), any(), any(), anyBoolean())).thenAnswer(invocation ->
                invocation.<java.util.function.Supplier<mygrant.impact.ProfileImpactAssessment>>getArgument(2).get());
        var policy = new PolicyDocument("2026-14439", "Policy", "DHS", "F-1,J-1",
                PolicyStatus.FINAL, SourceType.PRIMARY, LocalDate.of(2026, 7, 17),
                LocalDate.of(2026, 9, 15), "https://example.org/policy", "SUMMARY: Policy facts", "hash");
        when(policies.findById(21L)).thenReturn(policy);
        MockMvcBuilders.standaloneSetup(new PolicyRecommendationController(policies, assessments, store, limiter())).build()
                .perform(post("/api/v1/policies/21/recommendation").principal(() -> "user@example.org"))
                .andExpect(status().isOk());
        verify(assessments).assess(eq("user@example.org"), eq(policy),
                argThat(document -> document.documentNumber().equals("2026-14439")
                        && document.type().equals("FINAL")),
                argThat(classification -> classification.affectsF1() && classification.affectsJ1()));
        verify(store).getOrGenerate(eq("user@example.org"), eq(policy), any(), eq(false));
    }

    @Test
    void refreshRequestForcesANewAssessment() throws Exception {
        PolicyService policies = mock(PolicyService.class);
        ProfileImpactAssessmentService assessments = mock(ProfileImpactAssessmentService.class);
        PolicyRecommendationStore store = mock(PolicyRecommendationStore.class);
        when(store.getOrGenerate(anyString(), any(), any(), anyBoolean())).thenAnswer(invocation ->
                invocation.<java.util.function.Supplier<mygrant.impact.ProfileImpactAssessment>>getArgument(2).get());
        var policy = new PolicyDocument("2026-14439", "Policy", "DHS", "F-1", PolicyStatus.FINAL,
                SourceType.PRIMARY, LocalDate.of(2026, 7, 17), LocalDate.of(2026, 9, 15),
                "https://example.org/policy", "SUMMARY: Policy facts", "hash");
        when(policies.findById(21L)).thenReturn(policy);

        MockMvcBuilders.standaloneSetup(new PolicyRecommendationController(policies, assessments, store, limiter())).build()
                .perform(post("/api/v1/policies/21/recommendation?refresh=true").principal(() -> "user@example.org"))
                .andExpect(status().isOk());

        verify(store).getOrGenerate(eq("user@example.org"), eq(policy), any(), eq(true));
    }

    /** CS203-174: when the AI service still fails after its retries, the caller is told to try later. */
    @Test
    void aProviderOutageAnswers503WithRetryAfter() throws Exception {
        PolicyService policies = mock(PolicyService.class);
        ProfileImpactAssessmentService assessments = mock(ProfileImpactAssessmentService.class);
        PolicyRecommendationStore store = mock(PolicyRecommendationStore.class);
        when(policies.findById(21L)).thenReturn(new PolicyDocument("2026-14439", "Policy", "DHS", "F-1",
                PolicyStatus.FINAL, SourceType.PRIMARY, LocalDate.of(2026, 7, 17), null,
                "https://example.org/policy", "SUMMARY: Policy facts", "hash"));
        when(store.getOrGenerate(anyString(), any(), any(), anyBoolean())).thenReturn(
                new mygrant.impact.ProfileImpactAssessment(null, 0, "Urgency assessment is temporarily unavailable.",
                        java.util.List.of(), java.util.List.of(), java.util.List.of(), true, "OpenAI", "m", null, 0));

        MockMvcBuilders.standaloneSetup(new PolicyRecommendationController(policies, assessments, store, limiter()))
                .setControllerAdvice(new mygrant.common.GlobalExceptionHandler()).build()
                .perform(post("/api/v1/policies/21/recommendation").principal(() -> "user@example.org"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Retry-After", "30"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("The AI service is temporarily unavailable. Please try again later."));
    }

    @Test
    void anEmbeddingOutageFromSpringAiAlsoAnswers503() throws Exception {
        PolicyService policies = mock(PolicyService.class);
        when(policies.findById(21L)).thenThrow(new org.springframework.ai.retry.TransientAiException("HTTP 503 - down"));

        MockMvcBuilders.standaloneSetup(new PolicyRecommendationController(policies,
                        mock(ProfileImpactAssessmentService.class), mock(PolicyRecommendationStore.class), limiter()))
                .setControllerAdvice(new mygrant.common.GlobalExceptionHandler()).build()
                .perform(post("/api/v1/policies/21/recommendation").principal(() -> "user@example.org"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("HTTP 503"))));
    }

    /** CS203-176: the assessment endpoint has its own allowance, separate from questions. */
    @Test
    void tooManyAssessmentRequestsAnswer429WithRetryAfter() throws Exception {
        PolicyService policies = mock(PolicyService.class);
        PolicyRecommendationStore store = mock(PolicyRecommendationStore.class);
        var limiter = new mygrant.common.RateLimiter(new mygrant.common.RateLimitProperties(limit(10),
                limit(2), limit(10)));
        var mvc = MockMvcBuilders.standaloneSetup(new PolicyRecommendationController(policies,
                        mock(ProfileImpactAssessmentService.class), store, limiter))
                .setControllerAdvice(new mygrant.common.GlobalExceptionHandler()).build();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/policies/21/recommendation").principal(() -> "user@example.org"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/v1/policies/21/recommendation").principal(() -> "user@example.org"))
                .andExpect(status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("Retry-After"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("You are requesting assessments too quickly. Please wait a moment and try again."));
        // Another user, and the same user's questions, have their own allowances.
        mvc.perform(post("/api/v1/policies/21/recommendation").principal(() -> "other@example.org"))
                .andExpect(status().isOk());
        limiter.acquire(mygrant.common.RateLimiter.Allowance.QUESTIONS, "user@example.org");
        verify(store, times(3)).getOrGenerate(anyString(), any(), any(), anyBoolean());
    }

    private static mygrant.common.RateLimiter limiter() {
        return new mygrant.common.RateLimiter(new mygrant.common.RateLimitProperties(limit(10), limit(10), limit(10)));
    }

    private static mygrant.common.RateLimitProperties.Limit limit(int max) {
        return new mygrant.common.RateLimitProperties.Limit(true, max, java.time.Duration.ofMinutes(1));
    }
}
