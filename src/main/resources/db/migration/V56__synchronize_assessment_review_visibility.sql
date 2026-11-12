-- Legacy publication/review workflows and saved-assessment reads share one review state.
-- Derive the explicit fields for every existing writer, including human decisions and re-evaluation.
CREATE FUNCTION synchronize_recommendation_review_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- An explicit review-state update must remain authoritative even if publication was stale.
    IF TG_OP = 'UPDATE' AND NEW.publication_status = OLD.publication_status
        AND NEW.review_status IS DISTINCT FROM OLD.review_status THEN
        RETURN NEW;
    END IF;
    NEW.requires_review := NEW.publication_status <> 'PUBLISHED' OR NEW.reviewed_by_human;
    NEW.review_status := CASE
        WHEN NEW.publication_status = 'REJECTED' THEN 'REJECTED'
        WHEN NEW.publication_status <> 'PUBLISHED' THEN 'PENDING_REVIEW'
        WHEN NEW.reviewed_by_human THEN 'APPROVED'
        ELSE 'NOT_REQUIRED' END;
    RETURN NEW;
END;
$$;
CREATE TRIGGER recommendation_review_state
BEFORE INSERT OR UPDATE OF publication_status, assessment_json, reviewed_by_human
ON policy_recommendations FOR EACH ROW EXECUTE FUNCTION synchronize_recommendation_review_state();

-- Bring historical metadata into agreement without changing publication, JSON, or revision history.
UPDATE policy_recommendations SET
    requires_review = publication_status <> 'PUBLISHED' OR reviewed_by_human,
    review_status = CASE WHEN publication_status = 'REJECTED' THEN 'REJECTED'
        WHEN publication_status <> 'PUBLISHED' THEN 'PENDING_REVIEW'
        WHEN reviewed_by_human THEN 'APPROVED' ELSE 'NOT_REQUIRED' END;

CREATE FUNCTION synchronize_saved_assessment_review() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE impact_assessments SET
        requires_review = NEW.requires_review, review_status = NEW.review_status,
        urgency = NEW.severity, confidence = (NEW.assessment_json::jsonb ->> 'confidence')::integer,
        summary = NEW.assessment_json::jsonb ->> 'summary',
        reasons = COALESCE(NEW.assessment_json::jsonb -> 'reasons', '[]'::jsonb),
        recommended_actions = COALESCE(NEW.assessment_json::jsonb -> 'recommendedActions', '[]'::jsonb)
    WHERE recommendation_id = NEW.id AND user_id = NEW.user_id AND policy_id = NEW.policy_id
        AND status = 'COMPLETED' AND NEW.output_type = 'ASSESSMENT'
        AND NEW.assessment_json::jsonb ->> 'status' = 'COMPLETED';
    RETURN NEW;
END;
$$;
CREATE TRIGGER saved_assessment_review
AFTER UPDATE OF publication_status, assessment_json, reviewed_by_human ON policy_recommendations
FOR EACH ROW EXECUTE FUNCTION synchronize_saved_assessment_review();

-- Existing human decisions predate this trigger; expose their current content on the first read too.
UPDATE impact_assessments a SET
    requires_review = r.requires_review, review_status = r.review_status,
    urgency = r.severity, confidence = (r.assessment_json::jsonb ->> 'confidence')::integer,
    summary = r.assessment_json::jsonb ->> 'summary',
    reasons = COALESCE(r.assessment_json::jsonb -> 'reasons', '[]'::jsonb),
    recommended_actions = COALESCE(r.assessment_json::jsonb -> 'recommendedActions', '[]'::jsonb)
FROM policy_recommendations r
WHERE a.recommendation_id = r.id AND a.user_id = r.user_id AND a.policy_id = r.policy_id
    AND a.status = 'COMPLETED' AND r.output_type = 'ASSESSMENT'
    AND r.assessment_json::jsonb ->> 'status' = 'COMPLETED';
