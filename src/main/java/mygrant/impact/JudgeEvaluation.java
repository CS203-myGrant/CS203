package mygrant.impact;

import java.time.Instant;
import java.util.UUID;

/** Independent review, linked to the SHA-256 version of the exact candidate JSON. */
public record JudgeEvaluation(Verdict verdict, String reason, String model,
        Double faithfulness, Double relevance, Boolean citationsValid,
        String promptVersion, UUID evaluationId, String outputVersion, Instant evaluatedAt) {
    /** Legacy persisted results have no scores; do not manufacture them. */
    public JudgeEvaluation(Verdict verdict, String reason, String model) {
        this(verdict, reason, model, null, null, null, null, null, null, null);
    }
    public enum Verdict { YES, NO, UNAVAILABLE }
}
