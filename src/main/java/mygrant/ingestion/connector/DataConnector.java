package mygrant.ingestion.connector;

/**
 * Shared stages for one source import. Policy documents are deduplicated by
 * content hash; source-specific records may also have a unique natural key.
 * Connectors reuse their existing storage in {@link #persistDeduplicated(Object, Object)}.
 */
public interface DataConnector<Q, F, T, R> {
    SourceIdentity source();

    ConnectorFetch<F> fetch(Q request);

    T transform(ConnectorFetch<F> fetched, Q request);

    R persistDeduplicated(T transformed, Q request);

    ConnectorHealth health();

    R ingest(Q request);
}
