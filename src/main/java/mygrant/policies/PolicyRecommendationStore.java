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
import mygrant.impact.AssessmentReviewPolicy;
import mygrant.impact.ImpactAssessmentProperties;
import mygrant.impact.ImpactVisibility;
import mygrant.impact.ImpactAssessment;
import mygrant.impact.ImpactAssessmentRepository;
import mygrant.impact.AssessmentReviewStatus;
import mygrant.impact.AssessmentStatus;
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
    private final AssessmentReviewPolicy reviewPolicy;
    private final ImpactAssessmentRepository savedAssessments;
    private mygrant.impact.EvaluationStore evaluations;

    @org.springframework.beans.factory.annotation.Autowired
    void setEvaluationStore(mygrant.impact.EvaluationStore evaluations) { this.evaluations = evaluations; }

    /** Compatibility constructor for isolated callers using the default threshold. */
    public PolicyRecommendationStore(JdbcTemplate jdbc, ObjectMapper mapper,
            UserRepository users, UserProfileRepository profiles, ActionItemService actionItems) {
        this(jdbc, mapper, users, profiles, actionItems,
                new AssessmentReviewPolicy(new ImpactAssessmentProperties(60)), null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PolicyRecommendationStore(JdbcTemplate jdbc, ObjectMapper mapper,
            UserRepository users, UserProfileRepository profiles, ActionItemService actionItems,
            AssessmentReviewPolicy reviewPolicy, ImpactAssessmentRepository savedAssessments) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.users = users;
        this.profiles = profiles;
        this.actionItems = actionItems;
        this.reviewPolicy = reviewPolicy;
        this.savedAssessments = savedAssessments;
    }

    @Transactional
    public ProfileImpactAssessment getOrGenerate(String email, PolicyDocument policy,
            Supplier<ProfileImpactAssessment> generate) {
        var user = users.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
        var profile = profiles.findById(user.getId()).orElseGet(() -> UserProfile.empty(user.getId()));
        var savedIngestion = savedForProfile(user.getId(), policy.getId(), profile.getVersion());
        if (savedIngestion.isPresent()) {
            var saved = savedIngestion.get();
            return savedAssessments.findVisibleByIdAndUserId(saved.getId(), user.getId())
                    .map(this::asResponse).orElseGet(() -> saved.getStatus() == AssessmentStatus.UNAVAILABLE
                            ? unavailableNotice() : awaitingReview(0));
        }
        String hash = fingerprint(profile, policy);
        // Serialize concurrent clicks, including requests handled by different app instances.
        jdbc.execute("SELECT pg_advisory_xact_lock(" + user.getId().intValue() + "," + policy.getId().intValue() + ")");
        var human = jdbc.query("""
                SELECT r.assessment_json, r.publication_status FROM policy_recommendations r
                WHERE r.user_id = ? AND r.policy_id = ? AND r.input_hash = ?
                  AND r.reviewed_by_human = TRUE AND r.review_status = 'APPROVED' AND """
                + ImpactVisibility.VISIBLE + " FOR UPDATE", (rs, row) -> new String[] {rs.getString(1), rs.getString(2)}, user.getId(), policy.getId(), hash);
        if (!human.isEmpty()) {
            if (!"PUBLISHED".equals(human.getFirst()[1])) return awaitingReview(0);
            try { return mapper.readValue(human.getFirst()[0], ProfileImpactAssessment.class); }
            catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("Human-reviewed assessment is unreadable", e);
            }
        }
        List<String> saved = jdbc.queryForList("""
                SELECT r.assessment_json FROM policy_recommendations r
                WHERE r.user_id = ? AND r.policy_id = ? AND r.input_hash = ? AND """ + ImpactVisibility.VISIBLE, String.class, user.getId(), policy.getId(), hash);
        if (!saved.isEmpty()) {
            try {
                var assessment = mapper.readValue(saved.getFirst(), ProfileImpactAssessment.class);
                return assessment;
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                // A previously saved response may no longer match the current response format.
            }
        }
        // A waiting/rejected result must not be regenerated or leaked by the recommendation endpoint.
        Long hidden = jdbc.queryForObject("""
                SELECT count(*) FROM policy_recommendations r
                WHERE r.user_id = ? AND r.policy_id = ? AND r.input_hash = ? AND NOT """
                + ImpactVisibility.VISIBLE, Long.class, user.getId(), policy.getId(), hash);
        if (hidden != null && hidden > 0) return awaitingReview(0);
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
        result = reviewPolicy.apply(result);
        // Save review-bound candidates, but return only a safe notice until a reviewer approves them.
        if (result.status() == AssessmentStatus.COMPLETED) {
            Long id = jdbc.queryForObject("""
                    INSERT INTO policy_recommendations
                        (user_id, policy_id, input_hash, assessment_json, publication_status, severity,
                         requires_review, review_status)
                    VALUES (?, ?, ?, ?, '%s', '%s', %s, '%s')
                    ON CONFLICT (user_id, policy_id, input_hash) DO UPDATE SET
                        assessment_json = EXCLUDED.assessment_json,
                        publication_status = EXCLUDED.publication_status,
                        severity = EXCLUDED.severity,
                        requires_review = EXCLUDED.requires_review,
                        review_status = EXCLUDED.review_status,
                        version = policy_recommendations.version + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE policy_recommendations.reviewed_by_human = FALSE
                    RETURNING id
                    """.formatted(result.requiresHumanReview() ? "PENDING_REVIEW" : "PUBLISHED", severity(result),
                            result.requiresHumanReview(), result.requiresHumanReview() ? "PENDING_REVIEW" : "NOT_REQUIRED"),
                    Long.class, user.getId(), policy.getId(), hash, json(result));
            if (evaluations != null && result.evaluation() != null && result.evaluation().evaluationId() != null) {
                evaluations.attachAssessment(result.evaluation().evaluationId(), id, user.getId(), policy.getId());
            }
            if (!result.requiresHumanReview()) actionItems.syncForAssessment(id);
        }
        return result.status() == AssessmentStatus.COMPLETED && result.requiresHumanReview()
                ? awaitingReview(result.attempts()) : result;
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
                    (user_id, policy_id, input_hash, assessment_json, publication_status, severity, requires_review, review_status)
                VALUES (?, ?, ?, ?, 'PENDING_REVIEW', 'MEDIUM', TRUE, 'PENDING_REVIEW')
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
        var profile = profiles.findById(user.getId()).orElseGet(() -> UserProfile.empty(user.getId()));
        var savedIngestion = savedForProfile(user.getId(), policy.getId(), profile.getVersion());
        if (savedIngestion.isPresent()) {
            return savedAssessments.findVisibleByIdAndUserId(savedIngestion.get().getId(), user.getId()).map(this::asResponse);
        }
        List<String> saved = jdbc.queryForList("""
                SELECT r.assessment_json FROM policy_recommendations r
                WHERE r.user_id = ? AND r.policy_id = ? AND r.input_hash = ? AND """ + ImpactVisibility.VISIBLE, String.class, user.getId(), policy.getId(), fingerprint(profile, policy));
        if (saved.isEmpty()) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(mapper.readValue(saved.getFirst(), ProfileImpactAssessment.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            return java.util.Optional.empty();
        }
    }

    private java.util.Optional<ImpactAssessment> savedForProfile(Long userId, Long policyId, int version) {
        return savedAssessments == null ? java.util.Optional.empty()
                : savedAssessments.findByUserIdAndPolicyIdAndProfileVersion(userId, policyId, version);
    }

    private ProfileImpactAssessment asResponse(ImpactAssessment saved) {
        return new ProfileImpactAssessment(saved.getUrgency(), saved.getConfidence(), saved.getSummary(),
                saved.getReasons(), saved.getRecommendedActions(), List.of(),
                saved.isRequiresReview() && saved.getReviewStatus() != AssessmentReviewStatus.APPROVED,
                saved.getProvider(), saved.getModel(), null, 0);
    }

    private ProfileImpactAssessment unavailableNotice() {
        return new ProfileImpactAssessment(null, 0, "Urgency assessment is temporarily unavailable.",
                List.of(), List.of(), List.of(), true, "Assessment store", "", null, 0);
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
                true, "Review queue", "", null, attempts);
    }

    private static String severity(ProfileImpactAssessment assessment) {
        return assessment.urgency().name();
    }
}
