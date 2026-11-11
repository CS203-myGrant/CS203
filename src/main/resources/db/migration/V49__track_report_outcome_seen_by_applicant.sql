-- CS203-97: when the applicant saw the reviewer's outcome, for the unread badge in the header.
-- Reports closed before this column existed count as unseen, so their owners are told once.
ALTER TABLE recommendation_reports ADD COLUMN seen_by_applicant_at TIMESTAMPTZ;
CREATE INDEX recommendation_reports_unseen_idx ON recommendation_reports (user_id)
    WHERE status <> 'OPEN' AND seen_by_applicant_at IS NULL;
