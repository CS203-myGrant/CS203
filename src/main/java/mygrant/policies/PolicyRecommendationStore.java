package mygrant.policies;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import mygrant.actions.ActionItemService;
import mygrant.impact.ImpactReviewPolicy;
import mygrant.impact.ImpactUrgency;
import mygrant.impact.AssessmentStatus;
import mygrant.impact.ProfileImpactAssessment;
import mygrant.user.UserProfile;
import mygrant.user.UserProfileRepository;
import mygrant.user.UserRepository;
import mygrant.user.User;

/**
 * Durable per-user assessments; source or profile edits invalidate the saved result.
 * <p>
 * {@link #save} is the one write path for AI assessments, used by the policy page, rule-change
 * reassessment and background assessment after ingestion. It applies {@link ImpactReviewPolicy}
 * (publish, or hold for the reviewer queue), never changes a row a human has reviewed, and brings
 * the owner's checklist in line.
 */
@Service
public class PolicyRecommendationStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final UserRepository users;
    private final UserProfileRepository profiles;
    private final ActionItemService actionItems;
    private final ImpactReviewPolicy reviewPolicy;
    private mygrant.impact.EvaluationStore evaluations;

    /** What to do when the fingerprint already has a row that no human has reviewed. */
    public enum OnConflict {
        /** The applicant asked for a fresh assessment, or the old one was unreadable. */
        REPLACE_AI_RESULT,
        /** Background work: whatever is already stored for these exact inputs stands. */
        KEEP_EXISTING
    }

    /** The row that holds a fingerprint's assessment, and whether this call wrote it. */
    public record Saved(long id, boolean written, boolean published) {}

    /** The assessment stored for one user, policy and fingerprint. */
    public record Stored(long id, ProfileImpactAssessment assessment, boolean published, boolean reviewedByHuman) {}

    @org.springframework.beans.factory.annotation.Autowired
    void setEvaluationStore(mygrant.impact.EvaluationStore evaluations) { this.evaluations = evaluations; }

    @Autowired
    public PolicyRecommendationStore(JdbcTemplate jdbc, ObjectMapper mapper,
            UserRepository users, UserProfileRepository profiles, ActionItemService actionItems,
            ImpactReviewPolicy reviewPolicy) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.users = users;
        this.profiles = profiles;
        this.actionItems = actionItems;
        this.reviewPolicy = reviewPolicy;
    }

    @Transactional
    public ProfileImpactAssessment getOrGenerate(String email, PolicyDocument policy,
            Supplier<ProfileImpactAssessment> generate) {
        return getOrGenerate(email, policy, generate, false);
    }

    /**
     * Returns the current assessment or explicitly re-runs it when the applicant requests a
     * fresh assessment. Human-reviewed corrections remain authoritative and are never replaced,
     * and a draft held for review stays held: asking again, even for a fresh assessment, returns
     * "awaiting review" without calling the model.
     */
    @Transactional
    public ProfileImpactAssessment getOrGenerate(String email, PolicyDocument policy,
            Supplier<ProfileImpactAssessment> generate, boolean refresh) {
        var user = users.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
        var profile = profiles.findById(user.getId()).orElseGet(() -> UserProfile.empty(user.getId()));
        String hash = fingerprint(profile, policy);
        // Serialize concurrent clicks, including requests handled by different app instances.
        lock(user.getId(), policy.getId());
        var stored = rows(user.getId(), policy.getId(), hash, " FOR UPDATE");
        if (!stored.isEmpty()) {
            Row current = stored.getFirst();
            if (!current.published()) return awaitingReview(0);
            var saved = read(current.json());
            if (current.reviewedByHuman()) {
                return saved.orElseThrow(() -> new IllegalStateException("Human-reviewed assessment is unreadable"));
            }
            // A previously saved response may no longer match the current response format.
            if (!refresh && saved.isPresent()) return saved.get();
        }
        ProfileImpactAssessment result = generate.get();
        if (result.status() == AssessmentStatus.UNAVAILABLE && result.evaluation() != null
                && result.evaluation().evaluationId() != null && evaluations != null) {
            var evaluation = result.evaluation();
            var raw = evaluations.candidate(evaluation.evaluationId());
            if (raw.isPresent()) {
                try {
                    var candidate = mapper.readValue(raw.get(), ProfileImpactAssessment.class);
                    result = new ProfileImpactAssessment(candidate.urgency(), candidate.confidence(),
                            candidate.summary(), candidate.reasons(), candidate.recommendedActions(),
                            candidate.missingInformation(), true, candidate.provider(), candidate.model(),
                            evaluation, candidate.attempts());
                } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                    throw new IllegalStateException("Stored candidate is unreadable", exception);
                }
            }
        }
        // Generation outages with no candidate remain retryable. A successful result is published
        // or held for review by the shared review policy; a held one is not shown to the owner yet.
        if (result.status() == AssessmentStatus.COMPLETED) {
            Saved saved = save(user.getId(), policy.getId(), hash, result, OnConflict.REPLACE_AI_RESULT);
            if (!saved.published()) return awaitingReview(result.attempts());
        }
        return result;
    }

    /**
     * Stores a completed assessment for exactly these inputs, published or held for review by
     * {@link ImpactReviewPolicy}. A human-reviewed row for the fingerprint is never changed; with
     * {@link OnConflict#KEEP_EXISTING} no existing row is. Either way the result names the row that
     * now holds the fingerprint, so a caller can link to it.
     */
    @Transactional
    public Saved save(long userId, long policyId, String inputHash, ProfileImpactAssessment result,
            OnConflict onConflict) {
        if (result.status() != AssessmentStatus.COMPLETED) {
            throw new IllegalArgumentException("Only completed assessments are stored");
        }
        lock(userId, policyId);
        boolean published = !reviewPolicy.requiresReview(result);
        String conflict = onConflict == OnConflict.REPLACE_AI_RESULT ? """
                ON CONFLICT (user_id, policy_id, input_hash) DO UPDATE SET
                    assessment_json = EXCLUDED.assessment_json,
                    publication_status = EXCLUDED.publication_status,
                    severity = EXCLUDED.severity,
                    version = policy_recommendations.version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE policy_recommendations.reviewed_by_human = FALSE
                """ : "ON CONFLICT (user_id, policy_id, input_hash) DO NOTHING\n";
        List<Long> written = jdbc.queryForList("""
                INSERT INTO policy_recommendations
                    (user_id, policy_id, input_hash, assessment_json, publication_status, severity)
                VALUES (?, ?, ?, ?, ?, ?)
                """ + conflict + "RETURNING id", Long.class, userId, policyId, inputHash, json(result),
                published ? "PUBLISHED" : "PENDING_REVIEW", result.urgency().name());
        if (written.isEmpty()) {
            return jdbc.query("""
                    SELECT id, publication_status FROM policy_recommendations
                    WHERE user_id = ? AND policy_id = ? AND input_hash = ?
                    """, (rs, row) -> new Saved(rs.getLong(1), false, "PUBLISHED".equals(rs.getString(2))),
                    userId, policyId, inputHash).getFirst();
        }
        long id = written.getFirst();
        if (evaluations != null && result.evaluation() != null && result.evaluation().evaluationId() != null) {
            evaluations.attachAssessment(result.evaluation().evaluationId(), id, userId, policyId);
        }
        actionItems.syncForAssessment(id);
        return new Saved(id, true, published);
    }

    /** The assessment already stored for exactly these inputs, if it is readable. */
    @Transactional(readOnly = true)
    public Optional<Stored> find(long userId, long policyId, String inputHash) {
        return rows(userId, policyId, inputHash, "").stream()
                .flatMap(row -> read(row.json()).stream()
                        .filter(assessment -> assessment.status() == AssessmentStatus.COMPLETED)
                        .map(assessment -> new Stored(row.id(), assessment, row.published(), row.reviewedByHuman())))
                .findFirst();
    }

    private record Row(long id, String json, boolean published, boolean reviewedByHuman) {}

    private List<Row> rows(long userId, long policyId, String inputHash, String lock) {
        return jdbc.query("""
                SELECT id, assessment_json, publication_status, reviewed_by_human FROM policy_recommendations
                WHERE user_id = ? AND policy_id = ? AND input_hash = ?
                """ + lock, (rs, row) -> new Row(rs.getLong("id"), rs.getString("assessment_json"),
                        "PUBLISHED".equals(rs.getString("publication_status")), rs.getBoolean("reviewed_by_human")),
                userId, policyId, inputHash);
    }

    /** Queues an uncertain source extraction in the existing CS203-6 per-profile review queue. */
    @Transactional
    public void queueExtractionForReview(User user, PolicyDocument policy, String reason) {
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("extraction-review:" + policy.getId() + ":" + reason)
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        var draft = new ProfileImpactAssessment(ImpactUrgency.MEDIUM, 0,
                "Policy extraction needs human review before matching: " + reason,
                List.of("Source: " + policy.getSourceUrl(), "Policy: " + policy.getTitle()),
                List.of("Verify the extracted restriction scope and affected document-holder cases."),
                List.of("Affected countries and document-holder scope are not confirmed."),
                true, "Policy extractor", "deterministic", new mygrant.impact.JudgeEvaluation(
                        mygrant.impact.JudgeEvaluation.Verdict.NO,
                        "Extraction was ambiguous; no automatic match was made.", "System"), 0);
        jdbc.update("""
                INSERT INTO policy_recommendations
                    (user_id, policy_id, input_hash, assessment_json, publication_status, severity)
                VALUES (?, ?, ?, ?, 'PENDING_REVIEW', 'MEDIUM')
                ON CONFLICT (user_id, policy_id, input_hash) DO NOTHING
                """, user.getId(), policy.getId(), hash, json(draft));
    }

    /**
     * Identifies the exact profile version and policy an assessment was made for. Opening the
     * policy page reuses a stored result only while this is unchanged, so it must not depend on
     * anything but their stored values.
     */
    public String fingerprint(UserProfile profile, PolicyDocument policy) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((json(profile) + "\n" + json(policy)).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Reads only this user's current assessment; asking a question never regenerates it. */
    public java.util.Optional<ProfileImpactAssessment> findCurrent(String email, PolicyDocument policy) {
        var user = users.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
        var profile = profiles.findById(user.getId()).orElseGet(() -> UserProfile.empty(user.getId()));
        List<String> saved = jdbc.queryForList("""
                SELECT assessment_json FROM policy_recommendations
                WHERE user_id = ? AND policy_id = ? AND input_hash = ?
                  AND publication_status = 'PUBLISHED'
                """, String.class, user.getId(), policy.getId(), fingerprint(profile, policy));
        if (saved.isEmpty()) return java.util.Optional.empty();
        return read(saved.getFirst());
    }

    private void lock(long userId, long policyId) {
        jdbc.execute("SELECT pg_advisory_xact_lock(" + (int) userId + "," + (int) policyId + ")");
    }

    private Optional<ProfileImpactAssessment> read(String json) {
        try {
            return Optional.of(mapper.readValue(json, ProfileImpactAssessment.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize policy assessment", exception);
        }
    }

    private static ProfileImpactAssessment awaitingReview(int attempts) {
        return new ProfileImpactAssessment(null, 0,
                "This assessment is awaiting review.", List.of(), List.of(), List.of(),
                true, "Review queue", "", null, attempts, AssessmentStatus.AWAITING_REVIEW);
    }
}
