package mygrant.ingestion.connector;

import java.time.Instant;

/** Common health view; a failure retains the previous successful timestamp. */
public record ConnectorHealth(SourceIdentity source, Status status, Instant lastAttemptAt,
        Instant lastSuccessAt, Stage errorStage, String errorMessage) {
    public enum Status { NOT_CHECKED, OK, FAILED }
    public enum Stage { FETCH, TRANSFORM, PERSIST }

    public static ConnectorHealth notChecked(SourceIdentity source) {
        return new ConnectorHealth(source, Status.NOT_CHECKED, null, null, null, null);
    }

    public ConnectorHealth succeeded(Instant attemptAt, Instant successAt) {
        return new ConnectorHealth(source, Status.OK, attemptAt, successAt, null, null);
    }

    public ConnectorHealth failed(Instant attemptAt, Stage stage, RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return new ConnectorHealth(source, Status.FAILED, attemptAt, lastSuccessAt,
                stage, message);
    }
}
