ALTER TABLE user_profiles
    ADD COLUMN i765_filing_date DATE,
    ADD COLUMN i539_filing_date DATE;

CREATE TABLE forecast_series_selections (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    form_type VARCHAR(20) NOT NULL,
    form_category VARCHAR(200) NOT NULL,
    office VARCHAR(200) NOT NULL,
    PRIMARY KEY (user_id, form_type)
);

CREATE TABLE forecast_deadline_alerts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    form_type VARCHAR(20) NOT NULL,
    deadline_type VARCHAR(40) NOT NULL,
    filing_date DATE NOT NULL,
    deadline_date DATE NOT NULL,
    upper_bound_date DATE NOT NULL,
    source_observed_on DATE NOT NULL,
    uncertainty_explanation TEXT NOT NULL,
    message TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, form_type, deadline_type)
);
