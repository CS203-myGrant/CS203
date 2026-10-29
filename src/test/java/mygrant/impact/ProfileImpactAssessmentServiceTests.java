package mygrant.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.fasterxml.jackson.databind.ObjectMapper;

import mygrant.ingestion.classification.VisaClassification;
import mygrant.ingestion.federalregister.FederalRegisterDocument;
import mygrant.user.User;
import mygrant.user.UserProfile;
import mygrant.user.UserProfileRepository;
import mygrant.user.UserRepository;

class ProfileImpactAssessmentServiceTests {

    @Test
    void reportsProviderFailureWithoutExposingProviderResponse() {
        Harness harness = harness();
        harness.server.expect(requestTo("https://api.openai.test/chat/completions"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("sensitive provider details"));
        ProfileImpactAssessment result = harness.service.assess("student@example.com", document(),
                "DHS", "policy content", classification());
        assertThat(result.urgency()).isEqualTo(ImpactUrgency.UNAVAILABLE);
        assertThat(result.summary()).contains("temporarily unavailable").doesNotContain("sensitive");
        assertThat(result.requiresHumanReview()).isTrue();
        harness.server.verify();
    }

    @Test
    void approvedJudgeReturnsAssessment() throws Exception {
        Harness harness = harness();
        harness.respondWithCandidate("First attempt");
        when(harness.judge.evaluate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new JudgeEvaluation(JudgeEvaluation.Verdict.YES, "Supported", "claude-test"));

        ProfileImpactAssessment result = harness.service.assess("student@example.com", document(),
                "DHS", "policy content", classification());

        assertThat(result.urgency()).isEqualTo(ImpactUrgency.HIGH);
        assertThat(result.evaluation().verdict()).isEqualTo(JudgeEvaluation.Verdict.YES);
        assertThat(result.evaluation().model()).isEqualTo("claude-test");
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.requiresHumanReview()).isTrue();
        verify(harness.judge).evaluate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        harness.server.verify();
    }

    @Test
    void unavailableJudgeWithholdsUnverifiedAssessment() throws Exception {
        Harness harness = harness();
        harness.respondWithCandidate("Unverified claim");
        when(harness.judge.evaluate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new JudgeEvaluation(JudgeEvaluation.Verdict.UNAVAILABLE, "Unavailable", "claude-test"));

        ProfileImpactAssessment result = harness.service.assess("student@example.com", document(),
                "DHS", "policy content", classification());

        assertThat(result.urgency()).isEqualTo(ImpactUrgency.UNAVAILABLE);
        assertThat(result.summary()).doesNotContain("Unverified claim");
        assertThat(result.requiresHumanReview()).isTrue();
        assertThat(result.evaluation().verdict()).isEqualTo(JudgeEvaluation.Verdict.UNAVAILABLE);
        assertThat(result.attempts()).isEqualTo(1);
        harness.server.verify();
    }

    @Test
    void rejectedJudgeWithholdsAssessmentAndActions() throws Exception {
        Harness harness=harness();
        harness.respondWithCandidate("Unsupported claim");
        when(harness.judge.evaluate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new JudgeEvaluation(JudgeEvaluation.Verdict.NO,"Unsupported","judge"));
        var result=harness.service.assess("student@example.com",document(),"DHS","policy content",classification());
        assertThat(result.urgency()).isEqualTo(ImpactUrgency.UNAVAILABLE);
        assertThat(result.summary()).doesNotContain("Unsupported claim");
        assertThat(result.evaluation().verdict()).isEqualTo(JudgeEvaluation.Verdict.NO);
        harness.server.verify();
    }

    private Harness harness() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UserRepository users = mock(UserRepository.class);
        UserProfileRepository profiles = mock(UserProfileRepository.class);
        ImpactJudgeService judge = mock(ImpactJudgeService.class);
        User user = new User();
        user.setId(7L);
        user.setVisaType("F-1");
        when(users.findByEmail("student@example.com")).thenReturn(Optional.of(user));
        when(profiles.findById(7L)).thenReturn(Optional.of(UserProfile.fromUser(user)));
        return new Harness(server, judge, new ProfileImpactAssessmentService(builder, new ObjectMapper(),
                users, profiles, judge, "secret", "test-model", "https://api.openai.test"));
    }

    private record Harness(MockRestServiceServer server, ImpactJudgeService judge,
            ProfileImpactAssessmentService service) {
        void respondWithCandidate(String summary) throws Exception {
            String candidate = candidateJson(summary);
            String response = new ObjectMapper().writeValueAsString(
                    Map.of("choices", List.of(Map.of("message", Map.of("content", candidate)))));
            server.expect(requestTo("https://api.openai.test/chat/completions"))
                    .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        }

        String candidateJson(String summary) throws Exception {
            return new ObjectMapper().writeValueAsString(Map.of("urgency", "HIGH", "confidence", 80,
                    "summary", summary, "reasons", List.of("Reason"),
                    "recommendedActions", List.of("Review source"), "requiresHumanReview", false));
        }
    }

    @Test
    void reportsUnavailableWhenOpenAiIsNotConfigured() {
        UserRepository users = mock(UserRepository.class);
        User user = new User();
        user.setId(7L);
        when(users.findByEmail("student@example.com")).thenReturn(Optional.of(user));
        ProfileImpactAssessmentService service = new ProfileImpactAssessmentService(
                RestClient.builder(), new ObjectMapper(), users,
                mock(UserProfileRepository.class), mock(ImpactJudgeService.class), "", "test-model", "https://example.test");

        ProfileImpactAssessment result = service.assess("student@example.com", document(),
                "DHS", "policy content", classification());

        assertThat(result.urgency()).isEqualTo(ImpactUrgency.UNAVAILABLE);
        assertThat(result.summary()).contains("temporarily unavailable");
    }

    @Test
    void parsesStrictStructuredAssessmentFromOpenAi() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UserRepository users = mock(UserRepository.class);
        UserProfileRepository profiles = mock(UserProfileRepository.class);
        User user = new User();
        user.setId(7L);
        user.setEmail("student@example.com");
        user.setVisaType("F-1");
        UserProfile profile = UserProfile.fromUser(user);
        profile.setUpcomingTravelDate(LocalDate.of(2026, 10, 1));
        when(users.findByEmail("student@example.com")).thenReturn(Optional.of(user));
        when(profiles.findById(7L)).thenReturn(Optional.of(profile));

        server.expect(requestTo("https://api.openai.test/chat/completions"))
                .andExpect(header("Authorization", "Bearer secret"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"{\\"urgency\\":\\"HIGH\\",\\"confidence\\":92,\\"summary\\":\\"Travel may be affected.\\",\\"reasons\\":[\\"Upcoming travel overlaps the effective period.\\"],\\"recommendedActions\\":[\\"Review the primary source.\\"],\\"requiresHumanReview\\":true}"}}]}
                        """, MediaType.APPLICATION_JSON));

        ImpactJudgeService judge = mock(ImpactJudgeService.class);
        when(judge.evaluate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new JudgeEvaluation(JudgeEvaluation.Verdict.YES, "Supported", "claude-test"));
        ProfileImpactAssessmentService service = new ProfileImpactAssessmentService(builder,
                new ObjectMapper(), users, profiles, judge, "secret", "test-model", "https://api.openai.test");
        ProfileImpactAssessment result = service.assess("student@example.com", document(),
                "DHS", "policy content", classification());

        assertThat(result.urgency()).isEqualTo(ImpactUrgency.HIGH);
        assertThat(result.confidence()).isEqualTo(92);
        assertThat(result.recommendedActions()).containsExactly("Review the primary source.");
        assertThat(result.provider()).isEqualTo("OpenAI");
        assertThat(result.evaluation().verdict()).isEqualTo(JudgeEvaluation.Verdict.YES);
        server.verify();
    }

    private FederalRegisterDocument document() {
        return new FederalRegisterDocument("2026-14439", "Fixed admission period", "Rule",
                LocalDate.of(2026, 8, 27), LocalDate.of(2026, 9, 28),
                "https://example.test/document", "https://example.test/document.txt",
                List.of(new FederalRegisterDocument.Agency("DHS", "dhs")));
    }

    private VisaClassification classification() {
        return new VisaClassification(true, true, false, List.of("F-1", "J-1"),
                List.of("F-1:academic student"));
    }
}
