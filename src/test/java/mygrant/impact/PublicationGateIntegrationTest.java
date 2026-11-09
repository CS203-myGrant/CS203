package mygrant.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import mygrant.common.Confidence;
import mygrant.policies.*;
import mygrant.rag.dto.CitationResponse;
import mygrant.user.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

@SpringBootTest
@Transactional
class PublicationGateIntegrationTest {
    @Autowired EvaluationStore evaluations;
    @Autowired OutputPublicationService publication;
    @Autowired PolicyRecommendationStore recommendations;
    @Autowired PolicyQuestionHistory history;
    @Autowired ImpactReviewService review;
    @Autowired UserRepository users;
    @Autowired UserProfileRepository profiles;
    @Autowired PolicyDocumentRepository policies;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockBean ImpactJudgeService judge;
    User owner, admin;
    PolicyDocument policy;

    @BeforeEach void setup() {
        owner = users.saveAndFlush(new User("Owner", UUID.randomUUID()+"@example.org", "unused"));
        profiles.saveAndFlush(UserProfile.registrationProfile(owner.getId(), "F-1", null, null, null, null));
        admin = new User("Reviewer", UUID.randomUUID()+"@example.org", "unused");
        admin.setRole(UserRole.ADMIN);
        admin = users.saveAndFlush(admin);
        profiles.saveAndFlush(UserProfile.registrationProfile(admin.getId(), "F-1", null, null, null, null));
        policy = policies.saveAndFlush(new PolicyDocument("gate-"+UUID.randomUUID(), "Rule", "DHS", "F-1",
                PolicyStatus.FINAL, SourceType.PRIMARY, LocalDate.of(2026,1,1), null,
                "https://example.org/rule", "Rule applies.", "hash"));
    }

    ImpactJudgeService evaluator(String verdict) throws Exception {
        var builder = RestClient.builder();
        if (!"UNAVAILABLE".equals(verdict)) {
            var server = MockRestServiceServer.bindTo(builder).build();
            String decision = mapper.writeValueAsString(Map.of("verdict", verdict, "reason", "Grounding check",
                    "faithfulness", "YES".equals(verdict) ? 0.9 : 0.4, "relevance", 0.9));
            String body = mapper.writeValueAsString(Map.of("stop_reason", "end_turn", "content",
                    List.of(Map.of("type", "text", "text", decision))));
            server.expect(requestTo("https://judge.test/v1/messages")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        }
        var actual = new ImpactJudgeService(builder, mapper, "UNAVAILABLE".equals(verdict) ? "" : "test",
                "test-judge", "https://judge.test");
        actual.setEvaluationStore(evaluations);
        return actual;
    }

    ProfileImpactAssessment candidate() {
        return new ProfileImpactAssessment(ImpactUrgency.LOW, 90, "Raw draft", List.of("Reason"),
                List.of("Read the rule"), List.of(), false, "OpenAI", "test", null, 1);
    }

    UUID assessment(String verdict) throws Exception {
        var evaluation = evaluator(verdict).evaluate("Original evidence and profile", candidate());
        var withheld = new ProfileImpactAssessment(ImpactUrgency.UNAVAILABLE, 0, "Unavailable notice",
                List.of(), List.of(), List.of(), true, "OpenAI", "test", evaluation, 1);
        var response = recommendations.getOrGenerate(owner.getEmail(), policy, () -> withheld);
        assertThat(response.summary()).isEqualTo("Raw draft");
        assertThat(response.recommendedActions()).containsExactly("Read the rule");
        assertThat(response.requiresHumanReview()).isTrue();
        return evaluation.evaluationId();
    }

    void stubAssessment(String verdict) throws Exception {
        doAnswer(call -> evaluator(verdict).evaluate(call.getArgument(0), call.getArgument(1)))
                .when(judge).evaluate(anyString(), any());
    }

    void stubAnswer(String verdict) throws Exception {
        doAnswer(call -> evaluator(verdict).evaluateAnswer(call.getArgument(0), call.getArgument(1), call.getArgument(2)))
                .when(judge).evaluateAnswer(anyString(), anyString(), anyList());
    }

    @Test void evaluatorOutageKeepsRawAssessmentVisibleAndUnverified() throws Exception {
        UUID id = assessment("UNAVAILABLE");
        stubAssessment("NO");
        var first = publication.evaluate(id);
        var second = publication.evaluate(id);
        assertThat(second.reviewId()).isEqualTo(first.reviewId());
        assertThat(second.publicationStatus()).isEqualTo("PUBLISHED");
        assertThat(review.pending(admin.getEmail())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM action_items WHERE assessment_id = ?",
                Long.class, first.reviewId())).isEqualTo(1L);
    }

    @Test void passingReevaluationPublishesHeldAssessmentAndCreatesActions() throws Exception {
        UUID id = assessment("NO");
        stubAssessment("YES");
        var result = publication.evaluate(id);
        assertThat(result.publicationStatus()).isEqualTo("PUBLISHED");
        assertThat(result.reviewedByHuman()).isFalse();
        assertThat(review.pending(admin.getEmail())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM action_items WHERE assessment_id = ?",
                Long.class, result.reviewId())).isEqualTo(1);
        assertThat(recommendations.findCurrent(owner.getEmail(), policy)).isPresent();
    }

    @Test void unknownAndUnattachedOutputsCannotBeReevaluated() throws Exception {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> publication.evaluate(UUID.randomUUID()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(e -> assertThat(((org.springframework.web.server.ResponseStatusException)e).getStatusCode().value()).isEqualTo(404));
        UUID unattached = evaluator("NO").evaluate("Evidence", candidate()).evaluationId();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> publication.evaluate(unattached))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(e -> assertThat(((org.springframework.web.server.ResponseStatusException)e).getStatusCode().value()).isEqualTo(404));
    }

    @Test void humanCorrectionSurvivesAutomatedRejectionAndRegeneration() throws Exception {
        UUID id = assessment("NO");
        long reviewId = jdbc.queryForObject("SELECT id FROM policy_recommendations WHERE user_id = ?", Long.class, owner.getId());
        jdbc.update("UPDATE policy_recommendations SET publication_status = 'PENDING_REVIEW', "
                + "version = version + 1 WHERE id = ?", reviewId);
        review.publishCorrection(reviewId, admin.getEmail(), new ImpactReviewController.Correction(
                "LOW", 95, "Human correction", List.of("Verified"), List.of("Read official source"), List.of()));
        String corrected = jdbc.queryForObject("SELECT assessment_json FROM policy_recommendations WHERE id = ?", String.class, reviewId);
        stubAssessment("NO");
        var result = publication.evaluate(id);
        assertThat(result.reviewedByHuman()).isTrue();
        assertThat(result.publicationStatus()).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT assessment_json FROM policy_recommendations WHERE id = ?", String.class, reviewId)).isEqualTo(corrected);
        assertThat(recommendations.getOrGenerate(owner.getEmail(), policy, () -> { throw new AssertionError("Human verdict bypassed"); }).summary())
                .isEqualTo("Human correction");
    }

    @Test void recordedHumanHoldIsNotPublishedByAutomatedPass() throws Exception {
        UUID id = assessment("NO");
        jdbc.update("UPDATE policy_recommendations SET reviewed_by_human = TRUE WHERE user_id = ?", owner.getId());
        stubAssessment("YES");
        assertThat(publication.evaluate(id).publicationStatus()).isEqualTo("PUBLISHED");
        assertThat(recommendations.getOrGenerate(owner.getEmail(), policy, () -> { throw new AssertionError("Human hold bypassed"); }).urgency())
                .isEqualTo(ImpactUrgency.LOW);
    }

    UUID answer(String text, String verdict) throws Exception {
        var citations = List.of(new CitationResponse(policy.getExternalId(), "Rule", "DHS", "FINAL", "PRIMARY",
                "2026-01-01", null, policy.getSourceUrl(), 0.9, "chunk-A"));
        var evaluation = evaluator(verdict).evaluateAnswer("Original evidence [1]: Rule applies.", text, citations);
        var response = new PolicyChatService.Answer(evaluation.verdict() == JudgeEvaluation.Verdict.YES ? text : "Withheld",
                "Rule", policy.getSourceUrl(), true, evaluation, citations, Confidence.LOW);
        history.record(owner.getEmail(), policy.getId(), "What changed?", response);
        return evaluation.evaluationId();
    }

    @Test void invalidCitationAnswerUsesExistingReviewAndHumanCorrectionSurvivesReevaluation() throws Exception {
        UUID id = answer("Unsupported [2]", "UNAVAILABLE");
        stubAnswer("UNAVAILABLE");
        var first = publication.evaluate(id);
        assertThat(publication.evaluate(id).reviewId()).isEqualTo(first.reviewId());
        assertThat(review.pending(admin.getEmail())).hasSize(1);
        review.publishCorrection(first.reviewId(), admin.getEmail(), new ImpactReviewController.Correction(
                "INFO", 90, "Corrected answer [1]", List.of(), List.of(), List.of()));
        assertThat(publication.evaluate(id).reviewedByHuman()).isTrue();
        var saved = history.context(owner.getEmail(), policy.getId());
        assertThat(saved.getLast().message()).isEqualTo("Corrected answer [1]");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM policy_recommendations r WHERE r.user_id = ? AND " + ImpactVisibility.VISIBLE,
                Long.class, owner.getId())).isZero();
    }

    @Test void previouslyPublishedAnswerIsHeldIfEvaluatorLaterFails() throws Exception {
        UUID id = answer("Supported [1]", "YES");
        assertThat(review.pending(admin.getEmail())).isEmpty();
        stubAnswer("UNAVAILABLE");
        var result = publication.evaluate(id);
        assertThat(result.publicationStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(history.context(owner.getEmail(), policy.getId()).getLast().message()).contains("awaiting human review");
        assertThat(review.pending(admin.getEmail())).hasSize(1);
    }

    @Test void storedChunkIdentityIsReusedDuringAdminReevaluation() throws Exception {
        UUID id = answer("Supported [1]", "YES");
        assertThat(mapper.readTree(jdbc.queryForObject(
                "SELECT citations_json FROM stored_ai_outputs WHERE id = ?", String.class, id))
                .get(0).path("chunkId").asText()).isEqualTo("chunk-A");
        stubAnswer("YES");
        assertThat(publication.evaluate(id).evaluation().citationsValid()).isTrue();

        // A pre-chunk snapshot cannot be silently upgraded by trusting its source ID.
        var stored = mapper.readTree(jdbc.queryForObject(
                "SELECT citations_json FROM stored_ai_outputs WHERE id = ?", String.class, id));
        ((com.fasterxml.jackson.databind.node.ObjectNode)stored.get(0)).remove("chunkId");
        jdbc.update("UPDATE stored_ai_outputs SET citations_json = ? WHERE id = ?", mapper.writeValueAsString(stored), id);
        var held = publication.evaluate(id);
        assertThat(held.evaluation().citationsValid()).isFalse();
        assertThat(held.publicationStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(history.context(owner.getEmail(), policy.getId()).getLast().message()).contains("awaiting human review");
    }
}
