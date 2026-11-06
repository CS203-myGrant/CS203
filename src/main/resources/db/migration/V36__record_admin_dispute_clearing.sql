-- CS203-86: an admin clears a rule's disputed flag once a court lifts the block, with
-- POST /api/v1/policies/{id}/dispute/clear. The clearing is an append-only history step like any
-- other: recorded_by ADMIN, the admin's email, their reason, and the court order or news item that
-- lifted the block as its source. source_policy_id stays NULL: that source is a reference the admin
-- typed in, not a stored document. V35 is the sync_failures migration of the Federal Register
-- sync fix (fix/federal-register-sync-skip-failures).
ALTER TABLE policy_rule_history DROP CONSTRAINT policy_rule_history_recorded_by_check;
ALTER TABLE policy_rule_history ADD CONSTRAINT policy_rule_history_recorded_by_check
    CHECK (recorded_by IN ('INGESTION', 'BACKFILL', 'ADMIN'));

ALTER TABLE policy_rule_history ADD COLUMN actor_email VARCHAR(255);
ALTER TABLE policy_rule_history ADD COLUMN reason VARCHAR(2000);

-- An admin step always names the admin and gives a reason; no other step does.
ALTER TABLE policy_rule_history ADD CONSTRAINT policy_rule_history_admin_check
    CHECK ((recorded_by = 'ADMIN') = (actor_email IS NOT NULL AND reason IS NOT NULL));
