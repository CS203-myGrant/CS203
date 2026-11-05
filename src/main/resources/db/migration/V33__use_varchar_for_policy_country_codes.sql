ALTER TABLE policy_affected_countries
    ALTER COLUMN country_code TYPE VARCHAR(2)
    USING BTRIM(country_code)::VARCHAR(2);
