CREATE TABLE policy_country_restrictions (
    policy_id BIGINT NOT NULL REFERENCES policy_documents(id) ON DELETE CASCADE,
    rule_order INTEGER NOT NULL,
    country_code VARCHAR(2) NOT NULL CHECK (country_code = UPPER(country_code)),
    restriction_scope VARCHAR(20) NOT NULL CHECK (restriction_scope IN ('FULL', 'PARTIAL')),
    source_reference VARCHAR(2048) NOT NULL,
    exemption_wording TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (policy_id, rule_order)
);

ALTER TABLE policy_documents ADD COLUMN extraction_review_required BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE policy_documents ADD COLUMN special_document_holder_rule BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE immigration_documents ADD COLUMN issuing_authority VARCHAR(40);
ALTER TABLE immigration_documents ADD COLUMN policy_matching_consent BOOLEAN NOT NULL DEFAULT FALSE;
