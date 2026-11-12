-- CS203-177: realistic read data for a LOCAL load test. Never run this against staging or
-- production. Register the test account through the API first, then run, for example:
--   psql "$DATABASE_URL" -v email=loadtest@example.org -f loadtest/seed-local.sql
-- It adds 40 F-1 policies (with no embeddings), 20 published assessments of them for the
-- account (each both a policy_recommendations row and the visible impact_assessments row the
-- dashboard feed reads), three checklist items each, and 40 in-app alerts. Running it again adds
-- another set.
\set ON_ERROR_STOP on

BEGIN;

CREATE TEMP TABLE loadtest_owner AS SELECT id FROM users WHERE email = :'email';
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM loadtest_owner) THEN
        RAISE EXCEPTION 'Register the load-test account before seeding it';
    END IF;
END $$;

-- The dashboard endpoints need a completed profile; registration alone does not complete it.
UPDATE users SET profile_complete = TRUE WHERE id = (SELECT id FROM loadtest_owner);

CREATE TEMP TABLE loadtest_policies AS
WITH inserted AS (
    INSERT INTO policy_documents (external_id, title, agency, visa_type, status, source_type,
        publication_date, source_url, content, content_hash, indexed_at)
    SELECT 'loadtest-' || gen_random_uuid(), 'Load test F-1 rule ' || n, 'DHS', 'F-1', 'FINAL', 'PRIMARY',
           CURRENT_DATE - n, 'https://example.org/loadtest/' || n,
           'SUMMARY: A synthetic F-1 policy used only for local load testing. ' || repeat('Rule text. ', 400),
           md5(n::text) || md5((n + 1)::text), now()
    FROM generate_series(1, 40) AS n
    RETURNING id, title
)
SELECT id, title, row_number() OVER (ORDER BY id) AS n FROM inserted;

CREATE TEMP TABLE loadtest_assessments AS
WITH inserted AS (
    INSERT INTO policy_recommendations (user_id, policy_id, input_hash, assessment_json, publication_status, severity)
    SELECT (SELECT id FROM loadtest_owner), p.id, md5(random()::text) || md5(random()::text),
           json_build_object(
               'urgency', (ARRAY['HIGH', 'MEDIUM', 'LOW', 'NOT_APPLICABLE'])[1 + p.n % 4],
               'confidence', 85, 'summary', 'Synthetic assessment of ' || p.title,
               'reasons', json_build_array('Synthetic reason'),
               'recommendedActions', json_build_array('Check the source', 'Note the deadline', 'Ask your adviser'),
               'missingInformation', json_build_array(), 'requiresHumanReview', false,
               'provider', 'Load test', 'model', 'seed',
               'evaluation', json_build_object('verdict', 'YES', 'reason', 'Seeded', 'model', 'seed'),
               'attempts', 1, 'status', 'COMPLETED')::text,
           'PUBLISHED', (ARRAY['HIGH', 'MEDIUM', 'LOW', 'NOT_APPLICABLE'])[1 + p.n % 4]
    FROM loadtest_policies p WHERE p.n <= 20
    RETURNING id, user_id, policy_id, severity
)
SELECT * FROM inserted;

-- The saved-assessment feed (GET /api/v1/impact-assessments) reads these, linked as ingestion links them.
INSERT INTO impact_assessments (user_id, policy_id, profile_version, status, urgency, confidence, summary,
    reasons, recommended_actions, requires_review, review_status, provider, model, recommendation_id)
SELECT a.user_id, a.policy_id, 1, 'COMPLETED', a.severity, 85, 'Synthetic assessment of ' || p.title,
       '["Synthetic reason"]'::jsonb, '["Check the source", "Note the deadline", "Ask your adviser"]'::jsonb,
       FALSE, 'NOT_REQUIRED', 'Load test', 'seed', a.id
FROM loadtest_assessments a JOIN loadtest_policies p ON p.id = a.policy_id;

INSERT INTO action_items (assessment_id, user_id, item_key, position, title, priority)
SELECT a.id, a.user_id, md5(a.id || ':' || i), i, (ARRAY['Check the source', 'Note the deadline', 'Ask your adviser'])[i], 'MEDIUM'
FROM loadtest_assessments a CROSS JOIN generate_series(1, 3) AS i;

INSERT INTO notifications (user_id, policy_id, message, action_required)
SELECT (SELECT id FROM loadtest_owner), p.id, 'New policy that may affect F-1: ' || p.title, p.n % 5 = 0
FROM loadtest_policies p;

COMMIT;
