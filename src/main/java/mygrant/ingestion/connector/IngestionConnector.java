package mygrant.ingestion.connector;

/**
 * Shared stages for one source import. Connectors use their existing storage and
 * deduplication logic in {@link #persistDeduplicated(Object, Object)}.
 */
public interface IngestionConnector<Q, F, T, R> {
    SourceIdentity source();

    ConnectorFetch<F> fetch(Q request);

    T transform(ConnectorFetch<F> fetched, Q request);

    R persistDeduplicated(T transformed, Q request);

    ConnectorHealth health();

    R ingest(Q request);
}
