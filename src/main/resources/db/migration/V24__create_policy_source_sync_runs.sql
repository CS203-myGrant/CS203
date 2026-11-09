CREATE TABLE policy_source_sync_runs (
    id BIGSERIAL PRIMARY KEY,
    source_id BIGINT NOT NULL REFERENCES policy_sources(id) ON DELETE RESTRICT,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE,
    documents_fetched INTEGER NOT NULL DEFAULT 0,
    chunks_vectorised INTEGER NOT NULL DEFAULT 0,
    succeeded BOOLEAN NOT NULL,
    error TEXT
);

CREATE INDEX idx_policy_source_sync_runs_source_started
    ON policy_source_sync_runs (source_id, started_at DESC);
