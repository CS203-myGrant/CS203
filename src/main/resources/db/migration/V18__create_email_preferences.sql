-- One row per user who changed their alert emails; no row means the defaults
-- (critical alerts immediately, everything else in the daily digest).
CREATE TABLE email_preferences (
    user_id BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    critical_delivery VARCHAR(10) NOT NULL,
    other_delivery VARCHAR(10) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT email_preferences_modes_valid CHECK (
        critical_delivery IN ('IMMEDIATE', 'DIGEST', 'OFF')
        AND other_delivery IN ('IMMEDIATE', 'DIGEST', 'OFF'))
);
