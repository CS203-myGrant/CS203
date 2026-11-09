-- Keep the original candidate and exact evidence for admin re-evaluation.
-- Ownership is attached before an application response is returned.
CREATE TABLE stored_ai_outputs (
    id UUID PRIMARY KEY REFERENCES ai_output_evaluations(id),
    output_type VARCHAR(16) NOT NULL CHECK (output_type IN ('ANSWER', 'ASSESSMENT')),
    candidate_json TEXT NOT NULL,
    evidence_context TEXT NOT NULL,
    citations_json TEXT NOT NULL,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    policy_id BIGINT REFERENCES policy_documents(id) ON DELETE CASCADE,
    history_id BIGINT REFERENCES policy_question_history(id) ON DELETE CASCADE,
    review_id BIGINT UNIQUE REFERENCES policy_recommendations(id) ON DELETE CASCADE,
    latest_evaluation_json TEXT NOT NULL
);
ALTER TABLE policy_recommendations ADD COLUMN output_type VARCHAR(16) NOT NULL DEFAULT 'ASSESSMENT'
    CHECK (output_type IN ('ANSWER', 'ASSESSMENT'));
CREATE INDEX stored_ai_outputs_owner_idx ON stored_ai_outputs(user_id);

-- Account purge keeps an anonymised users row, so remove snapshots when owned
-- recommendations/history are deleted as well as on hard deletion of an owner.
