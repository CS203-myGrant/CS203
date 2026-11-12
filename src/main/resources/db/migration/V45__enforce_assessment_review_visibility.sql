-- The legacy feed/recommendation store must obey the same explicit review states as impact_assessments.
ALTER TABLE policy_recommendations
    ADD COLUMN requires_review BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN review_status VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUIRED';

-- Preserve human approvals. Classify older unreviewed rows using their saved model flag,
-- publication state and the historical default confidence threshold (60%).
UPDATE policy_recommendations SET
    requires_review = reviewed_by_human OR publication_status = 'PENDING_REVIEW'
        OR COALESCE((assessment_json::jsonb ->> 'requiresHumanReview')::boolean, TRUE)
        OR COALESCE((assessment_json::jsonb ->> 'confidence')::integer, 0) < 60,
    review_status = CASE
        WHEN reviewed_by_human AND publication_status = 'PUBLISHED' THEN 'APPROVED'
        WHEN reviewed_by_human OR publication_status = 'PENDING_REVIEW'
            OR COALESCE((assessment_json::jsonb ->> 'requiresHumanReview')::boolean, TRUE)
            OR COALESCE((assessment_json::jsonb ->> 'confidence')::integer, 0) < 60 THEN 'PENDING_REVIEW'
        ELSE 'NOT_REQUIRED' END;

-- Recording an actual publication change uses the existing revision trigger and a new version.
UPDATE policy_recommendations SET publication_status = 'PENDING_REVIEW', version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE review_status = 'PENDING_REVIEW' AND publication_status = 'PUBLISHED';
ALTER TABLE policy_recommendations ADD CONSTRAINT recommendation_review_state_check
    CHECK ((requires_review AND review_status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED'))
        OR (NOT requires_review AND review_status = 'NOT_REQUIRED'));
