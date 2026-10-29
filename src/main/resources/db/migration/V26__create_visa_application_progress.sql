CREATE TABLE visa_application_progress (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    visa_type VARCHAR(30) NOT NULL,
    interview_location VARCHAR(150) NOT NULL DEFAULT '',
    ds160_confirmation VARCHAR(30) NOT NULL DEFAULT '',
    interview_date DATE,
    intended_travel_date DATE,
    notes VARCHAR(2000) NOT NULL DEFAULT '',
    completed_steps INTEGER NOT NULL DEFAULT 0 CHECK (completed_steps BETWEEN 0 AND 127),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
