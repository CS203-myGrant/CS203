-- CS203-97: the reviewer's explanation to the applicant, required when a report is closed.
-- Existing closed reports predate the rule and keep a NULL note.
ALTER TABLE recommendation_reports ADD COLUMN resolution_note VARCHAR(1000);
ALTER TABLE recommendation_reports ADD CONSTRAINT recommendation_reports_note_when_open
    CHECK (status <> 'OPEN' OR resolution_note IS NULL);
