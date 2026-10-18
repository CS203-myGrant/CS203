CREATE TABLE user_profiles (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,

    version INTEGER NOT NULL DEFAULT 1,

    visa_type VARCHAR(20) NOT NULL,
    country_of_origin VARCHAR(100),
    country_of_citizenship VARCHAR(100),
    current_location VARCHAR(20),

    employment_status VARCHAR(30),
    employer VARCHAR(255),

    visa_start_date DATE,
    visa_expiry_date DATE,
    opt_start_date DATE,
    i140_filing_date DATE,
    priority_date DATE,

    academic_level VARCHAR(50),
    program_end_date DATE,
    upcoming_travel_date DATE,

    family_info JSONB NOT NULL DEFAULT '{}'::jsonb,
    custom_fields JSONB NOT NULL DEFAULT '{}'::jsonb,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO user_profiles (
    user_id,
    visa_type,
    academic_level,
    program_end_date,
    current_location,
    upcoming_travel_date
)
SELECT
    id,
    visa_type,
    academic_level,
    program_end_date,
    current_location,
    upcoming_travel_date
FROM users;