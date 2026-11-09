package mygrant.impact;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Append-only evaluation audit, plus replaceable candidate snapshots for publication review. */
@Repository
public class EvaluationStore {
    private final JdbcTemplate jdbc;
    public EvaluationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void saveCandidate(String type, JudgeEvaluation evaluation, String candidate,
            String context, String citations, String metadata) {
        jdbc.update("""
                INSERT INTO stored_ai_outputs
                    (id, output_type, candidate_json, evidence_context, citations_json, latest_evaluation_json)
                VALUES (?, ?, ?, ?, ?, ?)
                """, evaluation.evaluationId(), type, candidate, context, citations, metadata);
    }

    public java.util.Optional<String> candidate(java.util.UUID id) {
        return jdbc.queryForList("SELECT candidate_json FROM stored_ai_outputs WHERE id = ?",
                String.class, id).stream().findFirst();
    }

    public void attachAssessment(java.util.UUID id, long reviewId, long userId, long policyId) {
        // A regenerated assessment replaces the stored draft for this fingerprint;
        // evaluation audit entries remain append-only.
        jdbc.update("DELETE FROM stored_ai_outputs WHERE review_id = ? AND id <> ?", reviewId, id);
        jdbc.update("UPDATE stored_ai_outputs SET review_id = ?, user_id = ?, policy_id = ? WHERE id = ?",
                reviewId, userId, policyId, id);
    }

    public void save(String outputType, JudgeEvaluation evaluation) {
        jdbc.update("""
                INSERT INTO ai_output_evaluations
                    (id, output_type, output_version, verdict, faithfulness, relevance,
                     citations_valid, model, prompt_version, evaluated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, evaluation.evaluationId(), outputType, evaluation.outputVersion(),
                evaluation.verdict().name(), evaluation.faithfulness(), evaluation.relevance(),
                evaluation.citationsValid(), evaluation.model(), evaluation.promptVersion(),
                java.sql.Timestamp.from(evaluation.evaluatedAt()));
    }
}
