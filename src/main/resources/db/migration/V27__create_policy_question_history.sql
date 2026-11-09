CREATE TABLE policy_question_history (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    policy_id BIGINT REFERENCES policy_documents(id) ON DELETE CASCADE,
    question VARCHAR(2000) NOT NULL,
    response_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_policy_question_history_owner ON policy_question_history (user_id, policy_id, id);
