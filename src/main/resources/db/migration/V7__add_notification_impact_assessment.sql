ALTER TABLE notifications
    ADD COLUMN impact_severity VARCHAR(20),
    ADD COLUMN impact_explanation VARCHAR(500),
    ADD COLUMN impact_confidence VARCHAR(10);
