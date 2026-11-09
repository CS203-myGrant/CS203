package mygrant.impact;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Append-only audit of evaluations; raw candidate and profile text are not retained here. */
@Repository
public class EvaluationStore {
    private final JdbcTemplate jdbc;
    public EvaluationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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
