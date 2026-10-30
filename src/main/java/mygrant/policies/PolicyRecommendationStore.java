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
import mygrant.impact.ImpactUrgency;
import mygrant.impact.ProfileImpactAssessment;
import mygrant.user.UserProfile;
import mygrant.user.UserProfileRepository;
import mygrant.user.UserRepository;

/** Durable per-user assessments; source or profile edits invalidate the saved result. */
@Service
public class PolicyRecommendationStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final UserRepository users;
    private final UserProfileRepository profiles;

    public PolicyRecommendationStore(JdbcTemplate jdbc, ObjectMapper mapper,
            UserRepository users, UserProfileRepository profiles) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.users = users;
        this.profiles = profiles;
    }

    @Transactional
    public ProfileImpactAssessment getOrGenerate(String email, PolicyDocument policy,
            Supplier<ProfileImpactAssessment> generate) {
        var user = users.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
        var profile = profiles.findById(user.getId()).orElseGet(() -> UserProfile.fromUser(user));
        String hash = fingerprint(profile, policy);
        // Serialize concurrent clicks, including requests handled by different app instances.
        jdbc.execute("SELECT pg_advisory_xact_lock(" + user.getId().intValue() + "," + policy.getId().intValue() + ")");
        List<String> saved = jdbc.queryForList("""
                SELECT assessment_json FROM policy_recommendations
                WHERE user_id = ? AND policy_id = ? AND input_hash = ?
                  AND publication_status = 'PUBLISHED'
                """, String.class, user.getId(), policy.getId(), hash);
        if (!saved.isEmpty()) {
            try {
                var assessment = mapper.readValue(saved.getFirst(), ProfileImpactAssessment.class);
                if (approved(assessment)) return assessment;
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                // A previously saved response may no longer match the current response format.
            }
        }
        ProfileImpactAssessment result = generate.get();
        // Provider outages are retryable and are never persisted. Unapproved model
        // output is stored for review but never returned to an applicant.
        if (result.urgency() != ImpactUrgency.UNAVAILABLE) {
            boolean published = approved(result);
            jdbc.update("""
                    INSERT INTO policy_recommendations
                        (user_id, policy_id, input_hash, assessment_json, publication_status, severity)
                    VALUES (?, ?, ?, ?, '%s', '%s')
                    ON CONFLICT (user_id, policy_id, input_hash) DO UPDATE SET
                        assessment_json = EXCLUDED.assessment_json,
                        publication_status = EXCLUDED.publication_status,
                        severity = EXCLUDED.severity,
                        version = policy_recommendations.version + 1,
                        updated_at = CURRENT_TIMESTAMP
                    """.formatted(published ? "PUBLISHED" : "PENDING_REVIEW", severity(result)),
                    user.getId(), policy.getId(), hash, json(result));
            if (!published) {
                return new ProfileImpactAssessment(ImpactUrgency.UNAVAILABLE, 0,
                        "This assessment is awaiting review.", List.of(), List.of(), List.of(),
                        true, "Review queue", "", null, result.attempts());
            }
        }
        return result;
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
            return java.util.Optional.of(mapper.readValue(saved.getFirst(), ProfileImpactAssessment.class)).filter(PolicyRecommendationStore::approved);
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

    private static boolean approved(ProfileImpactAssessment assessment) {
        return assessment.urgency() != ImpactUrgency.UNAVAILABLE && assessment.evaluation() != null
                && assessment.evaluation().verdict() == mygrant.impact.JudgeEvaluation.Verdict.YES;
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
