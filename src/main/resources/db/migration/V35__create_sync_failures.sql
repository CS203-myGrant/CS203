-- A document that failed during a policy source sync. The sync records it here and carries on
-- with the other documents, so one bad document no longer fails the whole run and holds back every
-- later import. Later runs try it again until it succeeds (RESOLVED) or has failed the configured
-- number of times (PERMANENTLY_FAILED, 5 by default), after which it is skipped. Admins read it
-- through GET /api/v1/ingestion/sync-failures. V34 is left for the policy rules migration of the
-- open CS203-86 branch.
CREATE TABLE sync_failures (
    id BIGSERIAL PRIMARY KEY,
    source_id BIGINT NOT NULL REFERENCES policy_sources(id) ON DELETE RESTRICT,
    -- The source's own id for the document, such as a Federal Register document number.
    document_id VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INTEGER NOT NULL,
    last_error TEXT NOT NULL,
    first_failed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_failed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_sync_failures_source_document UNIQUE (source_id, document_id),
    CONSTRAINT chk_sync_failures_status
        CHECK (status IN ('RETRYING', 'PERMANENTLY_FAILED', 'RESOLVED')),
    CONSTRAINT chk_sync_failures_attempts
        CHECK (attempts > 0)
);

CREATE INDEX idx_sync_failures_source_status
    ON sync_failures (source_id, status);
