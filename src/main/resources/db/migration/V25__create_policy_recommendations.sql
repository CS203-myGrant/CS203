CREATE TABLE policy_recommendations (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    policy_id BIGINT NOT NULL REFERENCES policy_documents(id) ON DELETE CASCADE,
    input_hash VARCHAR(64) NOT NULL,
    assessment_json TEXT NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, policy_id)
);
