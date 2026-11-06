CREATE TABLE policy_affected_countries (
    policy_id BIGINT NOT NULL REFERENCES policy_documents(id) ON DELETE CASCADE,
    country_code CHAR(2) NOT NULL,
    PRIMARY KEY (policy_id, country_code),
    CHECK (country_code = UPPER(country_code))
);

CREATE TABLE policy_restriction_categories (
    policy_id BIGINT NOT NULL REFERENCES policy_documents(id) ON DELETE CASCADE,
    category VARCHAR(40) NOT NULL,
    PRIMARY KEY (policy_id, category)
);

CREATE TABLE policy_source_references (
    policy_id BIGINT NOT NULL REFERENCES policy_documents(id) ON DELETE CASCADE,
    reference_order INTEGER NOT NULL,
    source_reference VARCHAR(2048) NOT NULL,
    PRIMARY KEY (policy_id, reference_order)
);
