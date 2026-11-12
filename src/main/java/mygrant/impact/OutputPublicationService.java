package mygrant.impact;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mygrant.actions.ActionItemService;
import mygrant.rag.dto.CitationResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Shared publication transition for stored candidates. Human review always wins. */
@Service
public class OutputPublicationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ImpactJudgeService judge;
    private final ActionItemService actions;
    private final AssessmentReviewPolicy reviewPolicy;

    public OutputPublicationService(JdbcTemplate jdbc, ObjectMapper mapper,
            ImpactJudgeService judge, ActionItemService actions, AssessmentReviewPolicy reviewPolicy) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.judge = judge;
        this.actions = actions;
        this.reviewPolicy = reviewPolicy;
    }

    public record Result(UUID outputId, JudgeEvaluation evaluation, String publicationStatus,
            boolean reviewedByHuman, Long reviewId) {}

    private record Stored(UUID id, String type, String candidate, String context, String citations,
            Long userId, Long policyId, Long historyId, Long reviewId, String evaluation) {}

    private Stored load(UUID id) {
        var rows = jdbc.query("SELECT * FROM stored_ai_outputs WHERE id = ?", (rs, n) -> new Stored(
                rs.getObject("id", UUID.class), rs.getString("output_type"), rs.getString("candidate_json"),
                rs.getString("evidence_context"), rs.getString("citations_json"),
                rs.getObject("user_id", Long.class), rs.getObject("policy_id", Long.class),
                rs.getObject("history_id", Long.class), rs.getObject("review_id", Long.class),
                rs.getString("latest_evaluation_json")), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stored output not found");
        return rows.getFirst();
    }

    /** Called inside the history transaction, before returning any generated answer. */
    @Transactional
    public void attachAnswer(long historyId, long userId, Long policyId, UUID id) {
        lock(id);
        var output = load(id);
        if (policyId == null) {
            List<CitationResponse> sources = read(output.citations(), mapper.getTypeFactory()
                    .constructCollectionType(List.class, CitationResponse.class));
            for (var citation : sources) {
                var policies = jdbc.queryForList("SELECT id FROM policy_documents WHERE external_id = ?",
                        Long.class, citation.externalId());
                if (!policies.isEmpty()) { policyId = policies.getFirst(); break; }
            }
        }
        if (policyId == null) throw new IllegalStateException("Generated answer has no stored source");
        jdbc.update("UPDATE stored_ai_outputs SET history_id = ?, user_id = ?, policy_id = ? WHERE id = ?",
                historyId, userId, policyId, id);
        output = load(id);
        transition(output, read(output.evaluation(), JudgeEvaluation.class));
    }

    @Transactional
    public Result evaluate(UUID id) {
        lock(id);
        var output = load(id);
        if (output.userId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stored output has no owner");
        }
        // Lock the review row before calling the evaluator: a concurrent correction
        // either completes first or waits, then remains the last authoritative write.
        boolean human = humanReviewed(output.reviewId());
        JudgeEvaluation evaluation;
        if ("ANSWER".equals(output.type())) {
            List<CitationResponse> citations = read(output.citations(), mapper.getTypeFactory()
                    .constructCollectionType(List.class, CitationResponse.class));
            evaluation = judge.evaluateAnswer(output.context(), read(output.candidate(), String.class), citations);
        } else {
            evaluation = judge.evaluate(output.context(), read(output.candidate(), ProfileImpactAssessment.class));
        }
        // Re-evaluation attempts keep their audit entries but reuse the original
        // output identity and review row. Their temporary snapshots are unnecessary.
        jdbc.update("DELETE FROM stored_ai_outputs WHERE id = ?", evaluation.evaluationId());
        jdbc.update("UPDATE stored_ai_outputs SET latest_evaluation_json = ? WHERE id = ?", json(evaluation), id);
        if (human) return new Result(id, evaluation, status(output.reviewId()), true, output.reviewId());
        return transition(output, evaluation);
    }

    private Result transition(Stored output, JudgeEvaluation evaluation) {
        boolean human = humanReviewed(output.reviewId());
        if (human) return new Result(output.id(), evaluation, status(output.reviewId()), true, output.reviewId());
        boolean passed = evaluation.verdict() == JudgeEvaluation.Verdict.YES;
        String state = passed ? "PUBLISHED" : "PENDING_REVIEW";
        Long reviewId = output.reviewId();
        if ("ASSESSMENT".equals(output.type())) {
            if (reviewId == null) throw new IllegalStateException("Assessment is not attached to its review state");
            var candidate = read(output.candidate(), ProfileImpactAssessment.class);
            var assessed = reviewPolicy.apply(new ProfileImpactAssessment(candidate.urgency(), candidate.confidence(), candidate.summary(),
                    candidate.reasons(), candidate.recommendedActions(), candidate.missingInformation(),
                    candidate.requiresHumanReview() || !passed, candidate.provider(), candidate.model(),
                    evaluation, candidate.attempts()));
            state = assessed.requiresHumanReview() ? "PENDING_REVIEW" : "PUBLISHED";
            jdbc.update("""
                    UPDATE policy_recommendations SET assessment_json = ?, publication_status = ?,
                        requires_review = ?, review_status = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND reviewed_by_human = FALSE
                    """, json(assessed), state, assessed.requiresHumanReview(),
                    assessed.requiresHumanReview() ? "PENDING_REVIEW" : "NOT_REQUIRED", reviewId);
            actions.syncForAssessment(reviewId);
        } else {
            if (!passed && reviewId == null) {
                // Reuse CS203-6/42 review ownership, status, revision and correction
                // machinery. ANSWER entries never appear in impact feeds/checklists.
                var draft = new ProfileImpactAssessment(ImpactUrgency.LOW, 0,
                        read(output.candidate(), String.class), List.of("Policy Q&A requires independent review."),
                        List.of(), List.of(), true, "Policy Q&A", "", evaluation, 1);
                reviewId = jdbc.queryForObject("""
                        INSERT INTO policy_recommendations
                            (user_id, policy_id, input_hash, assessment_json, publication_status, severity, output_type)
                        VALUES (?, ?, ?, ?, 'PENDING_REVIEW', 'LOW', 'ANSWER')
                        ON CONFLICT (user_id, policy_id, input_hash) DO UPDATE
                            SET input_hash = EXCLUDED.input_hash
                        RETURNING id
                        """, Long.class, output.userId(), output.policyId(), "answer:" + output.id(), json(draft));
                jdbc.update("UPDATE stored_ai_outputs SET review_id = ? WHERE id = ?", reviewId, output.id());
            } else if (reviewId != null) {
                jdbc.update("""
                        UPDATE policy_recommendations SET publication_status = ?,
                            assessment_json = jsonb_set(assessment_json::jsonb, '{evaluation}', ?::jsonb)::text,
                            version = version + 1,
                            updated_at = CURRENT_TIMESTAMP WHERE id = ? AND reviewed_by_human = FALSE
                        """, state, json(evaluation), reviewId);
            }
            updateAnswer(output, passed ? read(output.candidate(), String.class)
                    : "This answer is awaiting human review. Please consult the original source or contact support.",
                    evaluation, !passed, false);
        }
        return new Result(output.id(), evaluation, state, false, reviewId);
    }

    /** Existing review correction summary is the corrected answer for ANSWER entries. */
    @Transactional
    public void publishHumanAnswer(long reviewId, String answer) {
        var rows = jdbc.queryForList("SELECT id FROM stored_ai_outputs WHERE review_id = ?", UUID.class, reviewId);
        for (UUID id : rows) {
            var output = load(id);
            updateAnswer(output, answer, new JudgeEvaluation(JudgeEvaluation.Verdict.YES,
                    "Approved by a human reviewer", "Human"), false, true);
        }
    }

    private void updateAnswer(Stored output, String answer, JudgeEvaluation evaluation, boolean held, boolean human) {
        if (output.historyId() == null) return;
        String response = jdbc.queryForObject("SELECT response_json FROM policy_question_history WHERE id = ?",
                String.class, output.historyId());
        ObjectNode node = (ObjectNode) read(response, JsonNode.class);
        node.put("answer", answer);
        // A judge pass does not clear pre-existing source uncertainty warnings.
        if (held) { node.put("requiresReview", true); node.put("confidence", "LOW"); }
        if (human) { node.put("requiresReview", false); node.put("reviewedByHuman", true); }
        node.set("evaluation", mapper.valueToTree(evaluation));
        jdbc.update("UPDATE policy_question_history SET response_json = ? WHERE id = ?", json(node), output.historyId());
    }

    private boolean humanReviewed(Long reviewId) {
        if (reviewId == null) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT reviewed_by_human FROM policy_recommendations WHERE id = ? FOR UPDATE", Boolean.class, reviewId));
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT publication_status FROM policy_recommendations WHERE id = ?", String.class, id);
    }

    private void lock(UUID id) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, id.toString());
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Unable to serialize stored output", e); }
    }

    private <T> T read(String json, Class<T> type) { return read(json, mapper.constructType(type)); }
    private <T> T read(String json, com.fasterxml.jackson.databind.JavaType type) {
        try { return mapper.readValue(json, type); }
        catch (Exception e) { throw new IllegalStateException("Stored output is unreadable", e); }
    }
}
