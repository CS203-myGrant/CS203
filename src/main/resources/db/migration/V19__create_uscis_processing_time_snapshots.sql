CREATE TABLE processing_time_snapshots (
    id BIGSERIAL PRIMARY KEY,
    source_name VARCHAR(40) NOT NULL DEFAULT 'USCIS_PROCESSING_TIMES',
    form_type VARCHAR(20) NOT NULL,
    form_category VARCHAR(200) NOT NULL,
    office VARCHAR(200) NOT NULL,
    observed_on DATE NOT NULL,
    percentile SMALLINT NOT NULL CHECK (percentile = 80),
    percentile_value NUMERIC(10, 2) NOT NULL CHECK (percentile_value > 0),
    median_value NUMERIC(10, 2),
    duration_unit VARCHAR(10) NOT NULL CHECK (duration_unit IN ('DAYS', 'MONTHS')),
    source_url VARCHAR(2048) NOT NULL,
    source_content_hash CHAR(64) NOT NULL,
    source_excerpt TEXT NOT NULL,
    imported_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT processing_time_snapshot_unique UNIQUE
        (source_name, form_type, form_category, office, observed_on, percentile, source_content_hash)
);

CREATE INDEX processing_time_snapshots_lookup
    ON processing_time_snapshots (form_type, form_category, office, observed_on DESC);

CREATE TABLE uscis_processing_time_health (
    source_name VARCHAR(40) PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    last_attempt_at TIMESTAMP WITH TIME ZONE,
    last_success_at TIMESTAMP WITH TIME ZONE,
    error_stage VARCHAR(20),
    error_message VARCHAR(1000)
);

INSERT INTO uscis_processing_time_health (source_name, status)
VALUES ('USCIS_PROCESSING_TIMES', 'NOT_CHECKED');
