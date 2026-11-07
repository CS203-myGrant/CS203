-- user_profiles is the sole owner of immigration and travel profile data.
ALTER TABLE users
    DROP COLUMN visa_type,
    DROP COLUMN academic_level,
    DROP COLUMN program_end_date,
    DROP COLUMN current_location,
    DROP COLUMN upcoming_travel_date;
