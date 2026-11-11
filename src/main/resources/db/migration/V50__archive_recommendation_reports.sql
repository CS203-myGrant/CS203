-- CS203-97: applicants may archive reports a reviewer has already answered, to tidy their list.
-- A report waiting for review cannot be archived.
ALTER TABLE recommendation_reports ADD COLUMN archived_at TIMESTAMPTZ;
ALTER TABLE recommendation_reports ADD CONSTRAINT recommendation_reports_archive_only_closed
    CHECK (archived_at IS NULL OR status <> 'OPEN');
