-- CS203-153: an ingestion assessment is published through the same per-user store as the
-- policy page (policy_recommendations), which the feed, review queue, checklist, reports and
-- revisions all key on. impact_assessments stays the per-profile-version processing record and
-- points at the row it was published as. A deleted recommendation leaves the processing record.
ALTER TABLE impact_assessments
    ADD COLUMN recommendation_id BIGINT REFERENCES policy_recommendations(id) ON DELETE SET NULL;
CREATE INDEX impact_assessments_recommendation_idx ON impact_assessments (recommendation_id)
    WHERE recommendation_id IS NOT NULL;
