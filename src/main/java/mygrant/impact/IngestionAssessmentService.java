package mygrant.impact;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import mygrant.ingestion.classification.VisaClassification;
import mygrant.ingestion.federalregister.FederalRegisterDocument;
import mygrant.policies.PolicyAssessmentInputs;
import mygrant.policies.PolicyDocumentRepository;
import mygrant.policies.PolicyRecommendationStore;
import mygrant.policies.SourceType;
import mygrant.user.UserProfile;
import mygrant.user.UserProfileRepository;
import mygrant.user.UserRepository;

/**
 * Fan out per captured profile; no transaction or DB connection is held during provider calls.
 * <p>
 * CS203-153: each completed result for an official (PRIMARY) policy is also published to its
 * owner through {@link PolicyRecommendationStore}, the same store and review policy as the policy
 * page, under the same fingerprint. Opening the policy page afterwards therefore reuses it, and a
 * result the owner already has for these exact inputs is reused here instead of calling the model.
 * News results stay processing records: news is secondary evidence and never alerts anyone.
 */
@Service
@EnableConfigurationProperties(ImpactAssessmentProperties.class)
public class IngestionAssessmentService {
    private static final Logger log = LoggerFactory.getLogger(IngestionAssessmentService.class);
    private final PolicyDocumentRepository policies;
    private final UserProfileRepository profiles;
    private final UserRepository users;
    private final ImpactAssessmentRepository results;
    private final ProfileImpactAssessmentService generator;
    private final PolicyRecommendationStore recommendations;
    private final ImpactReviewPolicy reviewPolicy;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    public IngestionAssessmentService(PolicyDocumentRepository policies, UserProfileRepository profiles,
            UserRepository users,
            ImpactAssessmentRepository results, ProfileImpactAssessmentService generator,
            PolicyRecommendationStore recommendations, ImpactReviewPolicy reviewPolicy,
            PlatformTransactionManager transactions) {
        this.policies = policies;
        this.profiles = profiles;
        this.users = users;
        this.results = results;
        this.generator = generator;
        this.recommendations = recommendations;
        this.reviewPolicy = reviewPolicy;
        this.read = new TransactionTemplate(transactions);
        read.setReadOnly(true);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.write = new TransactionTemplate(transactions);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void assessMatchingProfiles(Long policyId) {
        // Resolve lazy policy metadata and capture all matching profile versions before generation.
        Inputs inputs = read.execute(tx -> policies.findById(policyId).map(policy -> {
            var classification = PolicyAssessmentInputs.classification(policy);
            var visaTypes = classification.visaTypes().stream().map(String::trim)
                    .map(visa -> visa.toUpperCase(Locale.ROOT)).distinct().toList();
            List<UserProfile> matching = visaTypes.isEmpty() ? List.of() : profiles.findMatchingVisaTypes(visaTypes);
            boolean publish = policy.getSourceType() == SourceType.PRIMARY;
            // The fingerprint of each captured version, computed as the policy page computes it.
            Map<Long, String> fingerprints = new HashMap<>();
            if (publish) {
                matching.forEach(profile -> fingerprints.put(profile.getUserId(),
                        recommendations.fingerprint(profile, policy)));
            }
            return new Inputs(PolicyAssessmentInputs.document(policy), policy.getAgency(), policy.getContent(),
                    classification, PolicyAssessmentInputs.restrictionContext(policy), matching, publish,
                    Map.copyOf(fingerprints));
        }).orElse(null));
        if (inputs == null) return; // Deleted between commit and execution.
        for (UserProfile profile : inputs.profiles()) {
            try {
                assessOne(policyId, profile, inputs);
            } catch (RuntimeException failure) {
                // A recipient deleted during processing or a storage failure must not stop other recipients.
                log.warn("Could not save an assessment for policy {} ({})", policyId,
                        failure.getClass().getSimpleName());
            }
        }
    }

    /** Backfill one applicant who joined after an official policy was imported. */
    public void requestForProfile(Long policyId, String email) {
        Inputs inputs = read.execute(tx -> {
            var policy = policies.findById(policyId)
                    .orElseThrow(() -> new IllegalArgumentException("Policy was not found."));
            if (policy.getSourceType() != SourceType.PRIMARY) {
                throw new IllegalArgumentException("Only official policies can be requested here.");
            }
            Long userId = users.findByEmail(email)
                    .orElseThrow(() -> new IllegalArgumentException("Account was not found."))
                    .getId();
            UserProfile profile = profiles.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("Complete your profile first."));
            var classification = PolicyAssessmentInputs.classification(policy);
            boolean matching = classification.visaTypes().stream()
                    .anyMatch(visa -> visa.trim().equalsIgnoreCase(profile.getVisaType()));
            if (!matching) throw new IllegalArgumentException("This policy does not match your visa category.");
            return new Inputs(PolicyAssessmentInputs.document(policy), policy.getAgency(), policy.getContent(),
                    classification, PolicyAssessmentInputs.restrictionContext(policy), List.of(profile), true,
                    Map.of(userId, recommendations.fingerprint(profile, policy)));
        });
        assessOne(policyId, inputs.profiles().get(0), inputs);
    }

    private void assessOne(Long policyId, UserProfile profile, Inputs inputs) {
        if (alreadySaved(profile, policyId)) return; // Includes UNAVAILABLE: retries are a separate workflow.
        String fingerprint = inputs.fingerprints().get(profile.getUserId());
        // The owner may already hold an assessment of these exact inputs, e.g. from the policy page.
        Optional<PolicyRecommendationStore.Stored> existing = fingerprint == null ? Optional.empty()
                : recommendations.find(profile.getUserId(), policyId, fingerprint);
        ProfileImpactAssessment result;
        if (existing.isPresent()) {
            result = existing.get().assessment();
        } else {
            try {
                result = generator.assess(profile, inputs.document(), inputs.agency(), inputs.content(),
                        inputs.classification(), inputs.restrictionContext());
            } catch (RuntimeException failure) {
                // Includes provider adapters that throw instead of returning the existing UNAVAILABLE result.
                result = generator.unavailable("Urgency assessment is temporarily unavailable.");
            }
        }
        boolean unavailable = result.status() != AssessmentStatus.COMPLETED;
        AssessmentReviewStatus review = existing.map(IngestionAssessmentService::reviewStatus)
                .orElse(unavailable
                        ? (result.requiresHumanReview() ? AssessmentReviewStatus.PENDING_REVIEW
                                : AssessmentReviewStatus.NOT_REQUIRED)
                        : (reviewPolicy.requiresReview(result) ? AssessmentReviewStatus.PENDING_REVIEW
                                : AssessmentReviewStatus.NOT_REQUIRED));
        ImpactAssessment saved = new ImpactAssessment(profile.getUserId(), policyId, profile.getVersion(),
                unavailable ? AssessmentStatus.UNAVAILABLE : AssessmentStatus.COMPLETED,
                unavailable ? null : result.urgency(), unavailable ? null : result.confidence(),
                result.summary(), result.reasons(), result.recommendedActions(),
                review != AssessmentReviewStatus.NOT_REQUIRED, review, result.provider(), result.model());
        ProfileImpactAssessment completed = unavailable ? null : result;
        try {
            write.executeWithoutResult(tx -> {
                // First, so a second worker for the same profile version fails here and rolls back.
                ImpactAssessment row = results.saveAndFlush(saved);
                Long recommendationId = existing.map(PolicyRecommendationStore.Stored::id).orElse(null);
                if (recommendationId == null && completed != null && fingerprint != null) {
                    recommendationId = recommendations.save(profile.getUserId(), policyId, fingerprint, completed,
                            PolicyRecommendationStore.OnConflict.KEEP_EXISTING).id();
                }
                if (recommendationId != null) results.linkRecommendation(row.getId(), recommendationId);
            });
        } catch (DataIntegrityViolationException conflict) {
            // The losing transaction has already rolled back. The unique key also protects multiple app instances.
            if (!alreadySaved(profile, policyId)) throw conflict;
        }
    }

    /** The processing record's review state for a result its owner already has. */
    private static AssessmentReviewStatus reviewStatus(PolicyRecommendationStore.Stored stored) {
        if (stored.reviewedByHuman()) {
            return stored.published() ? AssessmentReviewStatus.APPROVED : AssessmentReviewStatus.PENDING_REVIEW;
        }
        return stored.published() ? AssessmentReviewStatus.NOT_REQUIRED : AssessmentReviewStatus.PENDING_REVIEW;
    }

    private boolean alreadySaved(UserProfile profile, Long policyId) {
        return results.findByUserIdAndPolicyIdAndProfileVersion(profile.getUserId(), policyId,
                profile.getVersion()).isPresent();
    }

    private record Inputs(FederalRegisterDocument document, String agency, String content,
            VisaClassification classification, String restrictionContext, List<UserProfile> profiles,
            boolean publish, Map<Long, String> fingerprints) {}
}
