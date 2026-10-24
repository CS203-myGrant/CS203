-- Outbox of alert emails. Each row is one email to one user, sent and retried by the
-- dispatcher. The id is a UUID because it doubles as the Resend Idempotency-Key, which is
-- scoped to the whole Resend account: sequential ids from separate databases sharing one
-- API key would collide.
CREATE TABLE email_deliveries (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind VARCHAR(10) NOT NULL,
    digest_date DATE,
    status VARCHAR(10) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    claimed_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(300),
    provider_message_id VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT email_deliveries_kind_valid CHECK (kind IN ('IMMEDIATE', 'DIGEST')),
    CONSTRAINT email_deliveries_status_valid
        CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'SKIPPED')),
    CONSTRAINT email_deliveries_digest_date_valid CHECK ((kind = 'DIGEST') = (digest_date IS NOT NULL)),
    -- At most one digest per user per day; immediate rows have no date and never conflict.
    CONSTRAINT uq_email_deliveries_user_digest_date UNIQUE (user_id, digest_date)
);

CREATE INDEX idx_email_deliveries_due ON email_deliveries (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_email_deliveries_sending ON email_deliveries (claimed_at) WHERE status = 'SENDING';

-- The email that carries each alert. Set once with a conditional update, so an alert is
-- emailed at most once.
ALTER TABLE notifications
    ADD COLUMN email_delivery_id UUID REFERENCES email_deliveries (id) ON DELETE SET NULL;

CREATE INDEX idx_notifications_email_delivery ON notifications (email_delivery_id);
CREATE INDEX idx_notifications_not_emailed ON notifications (created_at) WHERE email_delivery_id IS NULL;
