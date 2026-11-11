-- Tracking is explicit and separate from consent to use individual profile fields.
CREATE TABLE visa_bulletin_subscriptions (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    preference_type VARCHAR(20) NOT NULL,
    category VARCHAR(80) NOT NULL,
    chargeability_area VARCHAR(100) NOT NULL,
    priority_date DATE NOT NULL,
    enabled_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE visa_bulletin_alerts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    publication_month DATE NOT NULL,
    previous_month DATE NOT NULL,
    chart_type VARCHAR(30) NOT NULL,
    preference_type VARCHAR(20) NOT NULL,
    category VARCHAR(80) NOT NULL,
    chargeability_area VARCHAR(100) NOT NULL,
    previous_cutoff_kind VARCHAR(20) NOT NULL,
    previous_cutoff_date DATE,
    cutoff_kind VARCHAR(20) NOT NULL,
    cutoff_date DATE,
    priority_date DATE NOT NULL,
    source_url VARCHAR(2048) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT visa_bulletin_alert_unique UNIQUE (user_id, publication_month, chart_type),
    CONSTRAINT visa_bulletin_alert_cutoff_valid CHECK
        ((cutoff_kind = 'DATE' AND cutoff_date IS NOT NULL)
         OR (cutoff_kind IN ('CURRENT', 'UNAVAILABLE') AND cutoff_date IS NULL))
);
CREATE INDEX visa_bulletin_alerts_user_month ON visa_bulletin_alerts (user_id, publication_month DESC);
