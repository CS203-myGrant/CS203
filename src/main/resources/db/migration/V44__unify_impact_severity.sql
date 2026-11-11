-- Canonical profile impact: HIGH / MEDIUM / LOW / NOT_APPLICABLE.
-- Translate historical labels once; runtime parsers accept only the canonical scale.
CREATE FUNCTION pg_temp.canonical_impact_urgency(label TEXT) RETURNS TEXT AS $$
BEGIN
    RETURN CASE label
        WHEN 'HIGH' THEN 'HIGH' WHEN 'CRITICAL' THEN 'HIGH'
        WHEN 'MEDIUM' THEN 'MEDIUM' WHEN 'WARNING' THEN 'MEDIUM'
        WHEN 'LOW' THEN 'LOW' WHEN 'INFORMATIONAL' THEN 'LOW'
        WHEN 'NOT_APPLICABLE' THEN 'NOT_APPLICABLE'
        WHEN 'INFO' THEN 'NOT_APPLICABLE' WHEN 'NO_IMPACT' THEN 'NOT_APPLICABLE'
        WHEN 'UNAVAILABLE' THEN NULL
        ELSE NULL END;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

CREATE FUNCTION pg_temp.canonical_impact_json(payload TEXT, fallback TEXT DEFAULT NULL)
RETURNS TEXT AS $$
DECLARE
    document JSONB := payload::jsonb;
    label TEXT := COALESCE(document ->> 'urgency', fallback);
    urgency TEXT := pg_temp.canonical_impact_urgency(label);
BEGIN
    IF urgency IS NULL AND label IS DISTINCT FROM 'UNAVAILABLE'
        AND document ->> 'status' IS DISTINCT FROM 'UNAVAILABLE' THEN
        RAISE EXCEPTION 'Cannot migrate unknown impact urgency: %', label;
    END IF;
    RETURN jsonb_set(jsonb_set(document, '{urgency}', COALESCE(to_jsonb(urgency), 'null'::jsonb)),
        '{status}', to_jsonb(CASE WHEN urgency IS NULL THEN 'UNAVAILABLE' ELSE 'COMPLETED' END))::text;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

ALTER TABLE policy_recommendations DROP CONSTRAINT impact_severity_check;
ALTER TABLE policy_recommendations ALTER COLUMN severity DROP NOT NULL;
-- A representation migration must not masquerade as a new human/AI revision.
ALTER TABLE policy_recommendations DISABLE TRIGGER impact_revision_on_correction;
UPDATE policy_recommendations
SET assessment_json = pg_temp.canonical_impact_json(assessment_json, severity),
    severity = pg_temp.canonical_impact_json(assessment_json, severity)::jsonb ->> 'urgency';
UPDATE impact_assessment_revisions
SET assessment_json = pg_temp.canonical_impact_json(assessment_json);
ALTER TABLE policy_recommendations ENABLE TRIGGER impact_revision_on_correction;
ALTER TABLE policy_recommendations ADD CONSTRAINT impact_severity_check
    CHECK (severity IN ('HIGH', 'MEDIUM', 'LOW', 'NOT_APPLICABLE'));
-- Null severity represents unavailable processing, never a fifth severity level.
-- Provider/model audit snapshots and their content hashes remain byte-for-byte unchanged.
