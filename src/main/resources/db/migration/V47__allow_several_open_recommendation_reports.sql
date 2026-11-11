-- CS203-97: an applicant may report several separate problems with the same assessment while
-- earlier reports are still open. The service caps how many can be open at once.
DROP INDEX recommendation_reports_one_open_idx;
CREATE INDEX recommendation_reports_owner_idx ON recommendation_reports (assessment_id, user_id, created_at DESC);
