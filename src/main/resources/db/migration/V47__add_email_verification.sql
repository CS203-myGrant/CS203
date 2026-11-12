ALTER TABLE users
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN email_verification_token_hash VARCHAR(64),
    ADD COLUMN email_verification_expires_at TIMESTAMP WITH TIME ZONE;

-- WorkOS only accepts verified identities, so existing linked accounts retain their eligibility.
UPDATE users
SET email_verified = TRUE
WHERE workos_user_id IS NOT NULL;

CREATE INDEX users_email_verification_token_idx
    ON users (email_verification_token_hash)
    WHERE email_verification_token_hash IS NOT NULL;
