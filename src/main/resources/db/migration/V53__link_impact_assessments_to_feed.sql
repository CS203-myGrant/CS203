-- CS203-153 follow-up: each automatic assessment is published into the feed row that the
-- dashboard, review queue, action items and manager view already read. This records which one,
-- so a reviewer's decision on the feed row is reflected back on the stored assessment.
ALTER TABLE impact_assessments
    ADD COLUMN recommendation_id BIGINT REFERENCES policy_recommendations(id) ON DELETE SET NULL;
CREATE INDEX impact_assessments_recommendation_idx ON impact_assessments (recommendation_id)
    WHERE recommendation_id IS NOT NULL;
