CREATE TABLE visa_bulletin_imports (
    publication_month DATE PRIMARY KEY,
    content_hash CHAR(64) NOT NULL,
    source_url VARCHAR(2048) NOT NULL,
    imported_at TIMESTAMP WITH TIME ZONE NOT NULL
);

ALTER TABLE visa_bulletin_health
    ADD COLUMN error_stage VARCHAR(20);
