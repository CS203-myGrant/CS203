CREATE TABLE visa_bulletin_entries (
    id BIGSERIAL PRIMARY KEY,
    publication_month DATE NOT NULL,
    preference_type VARCHAR(20) NOT NULL,
    chart_type VARCHAR(30) NOT NULL,
    category VARCHAR(80) NOT NULL,
    chargeability_area VARCHAR(100) NOT NULL,
    cutoff_kind VARCHAR(20) NOT NULL,
    cutoff_date DATE,
    source_url VARCHAR(2048) NOT NULL,
    imported_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT visa_bulletin_entry_unique UNIQUE
        (publication_month, preference_type, chart_type, category, chargeability_area),
    CONSTRAINT visa_bulletin_cutoff_valid CHECK
        ((cutoff_kind = 'DATE' AND cutoff_date IS NOT NULL)
         OR (cutoff_kind IN ('CURRENT', 'UNAVAILABLE') AND cutoff_date IS NULL))
);

CREATE TABLE visa_bulletin_health (
    source_name VARCHAR(40) PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    last_attempt_at TIMESTAMP WITH TIME ZONE,
    last_success_at TIMESTAMP WITH TIME ZONE,
    publication_month DATE,
    source_url VARCHAR(2048),
    error_message VARCHAR(1000)
);

INSERT INTO visa_bulletin_health (source_name, status)
VALUES ('VISA_BULLETIN', 'NOT_CHECKED');
