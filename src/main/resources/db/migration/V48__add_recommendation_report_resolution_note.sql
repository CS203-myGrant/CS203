-- CS203-97: the reviewer's explanation to the applicant, required when a report is closed.
-- Reports closed before this rule have no note, so they get a placeholder the applicant can read.
ALTER TABLE recommendation_reports ADD COLUMN resolution_note VARCHAR(1000);
UPDATE recommendation_reports
    SET resolution_note = 'This report was closed before reviewers were asked to explain their decision.'
    WHERE status <> 'OPEN';
ALTER TABLE recommendation_reports ADD CONSTRAINT recommendation_reports_note_when_closed
    CHECK (status = 'OPEN' OR (resolution_note IS NOT NULL AND btrim(resolution_note) <> ''));
