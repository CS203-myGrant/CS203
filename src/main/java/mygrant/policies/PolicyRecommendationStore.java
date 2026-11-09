package mygrant.policies;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import mygrant.actions.ActionItemService;
import mygrant.impact.ImpactUrgency;
import mygrant.impact.ProfileImpactAssessment;
import mygrant.user.UserProfile;
import mygrant.user.UserProfileRepository;
import mygrant.user.UserRepository;
import mygrant.user.User;

/** Durable per-user assessments; source or profile edits invalidate the saved result. */
@Service
public class PolicyRecommendationStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final UserRepository users;
    private final UserProfileRepository profiles;
    private final ActionItemService actionItems;
    private mygrant.impact.EvaluationStore evaluations;

    @org.springframework.beans.factory.annotation.Autowired
    void setEvaluationStore(mygrant.impact.EvaluationStore evaluations) { this.evaluations = evaluations; }

    public PolicyRecommendationStore(JdbcTemplate jdbc, ObjectMapper mapper,
            UserRepository users, UserProfileRepository profiles, ActionItemService actionItems) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.users = users;
        this.profiles = profiles;
        this.actionItems = actionItems;
    }

    @Transactional
    public ProfileImpactAssessment getOrGenerate(String email, PolicyDocument policy,
            Supplier<ProfileImpactAssessment> generate) {
        var user = users.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
        var profile = profiles.findById(user.getId()).orElseGet(() -> UserProfile.fromUser(user));
        String hash = fingerprint(profile, policy);
        // Serialize concurrent clicks, including requests handled by different app instances.
        jdbc.execute("SELECT pg_advisory_xact_lock(" + user.getId().intValue() + "," + policy.getId().intValue() + ")");
        var human = jdbc.query("""
                SELECT assessment_json, publication_status FROM policy_recommendations
                WHERE user_id = ? AND policy_id = ? AND input_hash = ? AND reviewed_by_human = TRUE
                FOR UPDATE
                """, (rs, row) -> new String[] {rs.getString(1), rs.getString(2)}, user.getId(), policy.getId(), hash);
        if (!human.isEmpty()) {
            if (!"PUBLISHED".equals(human.getFirst()[1])) return awaitingReview(0);
            try { return mapper.readValue(human.getFirst()[0], ProfileImpactAssessment.class); }
            catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("Human-reviewed assessment is unreadable", e);
            }
        }
        List<String> saved = jdbc.queryForList("""
                SELECT assessment_json FROM policy_recommendations
                WHERE user_id = ? AND policy_id = ? AND input_hash = ?
                  AND publication_status = 'PUBLISHED'
                """, String.class, user.getId(), policy.getId(), hash);
        if (!saved.isEmpty()) {
            try {
                var assessment = mapper.readValue(saved.getFirst(), ProfileImpactAssessment.class);
                return assessment;
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                // A previously saved response may no longer match the current response format.
            }
        }
        ProfileImpactAssessment result = generate.get();
        if (result.urgency() == ImpactUrgency.UNAVAILABLE && result.evaluation() != null
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
        // Generation outages with no candidate remain retryable. A successful candidate
        // remains visible if independent review is unavailable or rejects it; its saved
        // evaluation and requiresHumanReview flag make that state explicit to the user.
        if (result.urgency() != ImpactUrgency.UNAVAILABLE) {
            Long id = jdbc.queryForObject("""
                    INSERT INTO policy_recommendations
                        (user_id, policy_id, input_hash, assessment_json, publication_status, severity)
                    VALUES (?, ?, ?, ?, '%s', '%s')
                    ON CONFLICT (user_id, policy_id, input_hash) DO UPDATE SET
                        assessment_json = EXCLUDED.assessment_json,
                        publication_status = EXCLUDED.publication_status,
                        severity = EXCLUDED.severity,
                        version = policy_recommendations.version + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE policy_recommendations.reviewed_by_human = FALSE
                    RETURNING id
                    """.formatted("PUBLISHED", severity(result)),
                    Long.class, user.getId(), policy.getId(), hash, json(result));
            if (evaluations != null && result.evaluation() != null && result.evaluation().evaluationId() != null) {
                evaluations.attachAssessment(result.evaluation().evaluationId(), id, user.getId(), policy.getId());
            }
            actionItems.syncForAssessment(id);
        }
        return result;
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
                VALUES (?, ?, ?, ?, 'PENDING_REVIEW', 'INFO')
                ON CONFLICT (user_id, policy_id, input_hash) DO NOTHING
                """, user.getId(), policy.getId(), hash, json(draft));
    }

    private String fingerprint(UserProfile profile, PolicyDocument policy) {
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
        var profile = profiles.findById(user.getId()).orElseGet(() -> UserProfile.fromUser(user));
        List<String> saved = jdbc.queryForList("""
                SELECT assessment_json FROM policy_recommendations
                WHERE user_id = ? AND policy_id = ? AND input_hash = ?
                  AND publication_status = 'PUBLISHED'
                """, String.class, user.getId(), policy.getId(), fingerprint(profile, policy));
        if (saved.isEmpty()) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(mapper.readValue(saved.getFirst(), ProfileImpactAssessment.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            return java.util.Optional.empty();
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
        return new ProfileImpactAssessment(ImpactUrgency.UNAVAILABLE, 0,
                "This assessment is awaiting review.", List.of(), List.of(), List.of(),
                true, "Review queue", "", null, attempts);
    }

    private static String severity(ProfileImpactAssessment assessment) {
        return switch (assessment.urgency()) {
            case HIGH -> "CRITICAL";
            case MEDIUM -> "MEDIUM";
            case LOW -> "LOW";
            case NOT_APPLICABLE, UNAVAILABLE -> "INFO";
        };
    }
}
