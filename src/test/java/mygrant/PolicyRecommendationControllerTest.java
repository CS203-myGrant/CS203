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
        when(store.getOrGenerate(anyString(), any(), any())).thenAnswer(invocation ->
                invocation.<java.util.function.Supplier<mygrant.impact.ProfileImpactAssessment>>getArgument(2).get());
        var policy = new PolicyDocument("2026-14439", "Policy", "DHS", "F-1,J-1",
                PolicyStatus.FINAL, SourceType.PRIMARY, LocalDate.of(2026, 7, 17),
                LocalDate.of(2026, 9, 15), "https://example.org/policy", "SUMMARY: Policy facts", "hash");
        when(policies.findById(21L)).thenReturn(policy);
        MockMvcBuilders.standaloneSetup(new PolicyRecommendationController(policies, assessments, store)).build()
                .perform(post("/api/v1/policies/21/recommendation").principal(() -> "user@example.org"))
                .andExpect(status().isOk());
        verify(assessments).assess(eq("user@example.org"),
                argThat(document -> document.documentNumber().equals("2026-14439")
                        && document.type().equals("FINAL")), eq("DHS"), eq("SUMMARY: Policy facts"),
                argThat(classification -> classification.affectsF1() && classification.affectsJ1()));
    }
}
