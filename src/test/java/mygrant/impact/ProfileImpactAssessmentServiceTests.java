package mygrant.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
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
    void reportsRateLimitWithoutExposingProviderResponse() {
        Harness harness = harness();
        harness.server.expect(requestTo("https://api.groq.test/chat/completions"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("sensitive provider details"));
        ProfileImpactAssessment result = harness.service.assess("student@example.com", document(),
                "DHS", "policy content", classification());
        assertThat(result.urgency()).isEqualTo(ImpactUrgency.UNAVAILABLE);
        assertThat(result.summary()).contains("rate or quota limit").doesNotContain("sensitive");
        assertThat(result.requiresHumanReview()).isTrue();
        harness.server.verify();
    }

    @Test
    void retriesRejectedAssessmentThenReturnsOnlyApprovedResult() throws Exception {
        Harness harness = harness();
        harness.respondWithCandidate("First attempt");
        harness.respondWithCandidate("Revised assessment");
        when(harness.judge.evaluate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new JudgeEvaluation(JudgeEvaluation.Verdict.NO, "Action lacks evidence", "qwen-test"))
                .thenReturn(new JudgeEvaluation(JudgeEvaluation.Verdict.YES, "Supported", "qwen-test"));

        ProfileImpactAssessment result = harness.service.assess("student@example.com", document(),
                "DHS", "policy content", classification());

        assertThat(result.summary()).isEqualTo("Revised assessment");
        assertThat(result.evaluation().verdict()).isEqualTo(JudgeEvaluation.Verdict.YES);
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(result.requiresHumanReview()).isTrue();
        verify(harness.judge, times(2)).evaluate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        harness.server.verify();
    }

    @Test
    void rejectedTwiceReturnsSafeFallbackInsteadOfRawAssessment() throws Exception {
        Harness harness = harness();
        harness.respondWithCandidate("Unverified claim");
        harness.respondWithCandidate("Still unverified");
        when(harness.judge.evaluate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new JudgeEvaluation(JudgeEvaluation.Verdict.NO, "Unsupported", "qwen-test"));

        ProfileImpactAssessment result = harness.service.assess("student@example.com", document(),
                "DHS", "policy content", classification());

        assertThat(result.urgency()).isEqualTo(ImpactUrgency.UNAVAILABLE);
        assertThat(result.summary()).doesNotContain("unverified");
        assertThat(result.requiresHumanReview()).isTrue();
        assertThat(result.evaluation().verdict()).isEqualTo(JudgeEvaluation.Verdict.NO);
        assertThat(result.attempts()).isEqualTo(2);
        harness.server.verify();
    }

    @Test
    void judgeFailureReturnsFallbackWithoutRetryingGenerator() throws Exception {
        Harness harness = harness();
        harness.respondWithCandidate("Unverified claim");
        when(harness.judge.evaluate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new JudgeEvaluation(JudgeEvaluation.Verdict.UNAVAILABLE, "Unavailable", "qwen-test"));

        ProfileImpactAssessment result = harness.service.assess("student@example.com", document(),
                "DHS", "policy content", classification());

        assertThat(result.urgency()).isEqualTo(ImpactUrgency.UNAVAILABLE);
        assertThat(result.requiresHumanReview()).isTrue();
        assertThat(result.attempts()).isEqualTo(1);
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
                users, profiles, judge, "secret", "test-model", "https://api.groq.test"));
    }

    private record Harness(MockRestServiceServer server, ImpactJudgeService judge,
            ProfileImpactAssessmentService service) {
        void respondWithCandidate(String summary) throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            String candidate = mapper.writeValueAsString(Map.of("urgency", "HIGH", "confidence", 80,
                    "summary", summary, "reasons", List.of("Reason"),
                    "recommendedActions", List.of("Review source"), "requiresHumanReview", false));
            String response = mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", candidate)))));
            server.expect(requestTo("https://api.groq.test/chat/completions"))
                    .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        }
    }

    @Test
    void reportsUnavailableWhenGroqIsNotConfigured() {
        ProfileImpactAssessmentService service = new ProfileImpactAssessmentService(
                RestClient.builder(), new ObjectMapper(), mock(UserRepository.class),
                mock(UserProfileRepository.class), mock(ImpactJudgeService.class), "", "test-model", "https://example.test");

        ProfileImpactAssessment result = service.assess("student@example.com", document(),
                "DHS", "policy content", classification());

        assertThat(result.urgency()).isEqualTo(ImpactUrgency.UNAVAILABLE);
        assertThat(result.summary()).contains("GROQ_API_KEY");
    }

    @Test
    void parsesStrictStructuredAssessmentFromGroq() {
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

        server.expect(requestTo("https://api.groq.test/chat/completions"))
                .andExpect(header("Authorization", "Bearer secret"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"{\\"urgency\\":\\"HIGH\\",\\"confidence\\":92,\\"summary\\":\\"Travel may be affected.\\",\\"reasons\\":[\\"Upcoming travel overlaps the effective period.\\"],\\"recommendedActions\\":[\\"Review the primary source.\\"],\\"requiresHumanReview\\":true}"}}]}
                        """, MediaType.APPLICATION_JSON));

        ImpactJudgeService judge = mock(ImpactJudgeService.class);
        when(judge.evaluate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new JudgeEvaluation(JudgeEvaluation.Verdict.YES, "Supported", "qwen-test"));
        ProfileImpactAssessmentService service = new ProfileImpactAssessmentService(builder,
                new ObjectMapper(), users, profiles, judge, "secret", "test-model", "https://api.groq.test");
        ProfileImpactAssessment result = service.assess("student@example.com", document(),
                "DHS", "policy content", classification());

        assertThat(result.urgency()).isEqualTo(ImpactUrgency.HIGH);
        assertThat(result.confidence()).isEqualTo(92);
        assertThat(result.recommendedActions()).containsExactly("Review the primary source.");
        assertThat(result.provider()).isEqualTo("Groq");
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
