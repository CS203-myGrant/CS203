package mygrant.impact;

/** Independent review of a generated impact assessment. */
public record JudgeEvaluation(Verdict verdict, String reason, String model) {
    public enum Verdict { YES, NO, UNAVAILABLE }
}
