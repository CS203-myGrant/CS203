CREATE TABLE policy_sources (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(30) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    poll_interval_seconds INTEGER NOT NULL,
    last_success_at TIMESTAMP WITH TIME ZONE,
    last_failure_at TIMESTAMP WITH TIME ZONE,
    last_error TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_policy_sources_type
        CHECK (type IN ('FEDERAL_REGISTER', 'USCIS', 'VISA_BULLETIN', 'NEWS', 'RSS')),
    CONSTRAINT chk_policy_sources_poll_interval
        CHECK (poll_interval_seconds > 0)
);

CREATE INDEX idx_policy_sources_enabled
    ON policy_sources (enabled);

CREATE TABLE policy_source_change_log (
    id BIGSERIAL PRIMARY KEY,
    source_id BIGINT NOT NULL REFERENCES policy_sources(id) ON DELETE RESTRICT,
    actor_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    actor_email VARCHAR(100) NOT NULL,
    action VARCHAR(20) NOT NULL,
    details TEXT,
    changed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_policy_source_change_action
        CHECK (action IN ('CREATE', 'UPDATE', 'ENABLE', 'DISABLE'))
);

CREATE INDEX idx_policy_source_change_log_source_changed
    ON policy_source_change_log (source_id, changed_at DESC);
