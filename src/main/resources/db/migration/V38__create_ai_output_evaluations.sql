-- Content-addressed output version identifies the candidate, including rejected candidates.
-- No raw candidate, profile, question, or judge rationale is retained in this audit table.
CREATE TABLE ai_output_evaluations (
    id UUID PRIMARY KEY,
    output_type VARCHAR(16) NOT NULL CHECK (output_type IN ('ANSWER', 'ASSESSMENT')),
    output_version CHAR(64) NOT NULL,
    verdict VARCHAR(16) NOT NULL CHECK (verdict IN ('YES', 'NO', 'UNAVAILABLE')),
    faithfulness DOUBLE PRECISION CHECK (faithfulness BETWEEN 0 AND 1),
    relevance DOUBLE PRECISION CHECK (relevance BETWEEN 0 AND 1),
    citations_valid BOOLEAN,
    model TEXT NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL,
    CHECK (verdict <> 'YES' OR (faithfulness IS NOT NULL AND relevance IS NOT NULL
        AND faithfulness >= 0.8 AND relevance >= 0.8 AND citations_valid IS DISTINCT FROM FALSE))
);
CREATE INDEX ai_output_evaluations_version_idx ON ai_output_evaluations(output_version, evaluated_at);
