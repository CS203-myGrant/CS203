package mygrant.impact;

import java.util.List;
import java.util.Locale;

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
import mygrant.user.UserProfile;
import mygrant.user.UserProfileRepository;

/** Fan out per captured profile; no transaction or DB connection is held during provider calls. */
@Service
@EnableConfigurationProperties(ImpactAssessmentProperties.class)
public class IngestionAssessmentService {
    private static final Logger log = LoggerFactory.getLogger(IngestionAssessmentService.class);
    private final PolicyDocumentRepository policies;
    private final UserProfileRepository profiles;
    private final ImpactAssessmentRepository results;
    private final ProfileImpactAssessmentService generator;
    private final ImpactAssessmentProperties properties;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    public IngestionAssessmentService(PolicyDocumentRepository policies, UserProfileRepository profiles,
            ImpactAssessmentRepository results, ProfileImpactAssessmentService generator,
            ImpactAssessmentProperties properties, PlatformTransactionManager transactions) {
        this.policies = policies;
        this.profiles = profiles;
        this.results = results;
        this.generator = generator;
        this.properties = properties;
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
            return new Inputs(PolicyAssessmentInputs.document(policy), policy.getAgency(), policy.getContent(),
                    classification, PolicyAssessmentInputs.restrictionContext(policy),
                    visaTypes.isEmpty() ? List.of() : profiles.findMatchingVisaTypes(visaTypes));
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

    private void assessOne(Long policyId, UserProfile profile, Inputs inputs) {
        if (alreadySaved(profile, policyId)) return; // Includes UNAVAILABLE: retries are a separate workflow.
        ProfileImpactAssessment result;
        try {
            result = generator.assess(profile, inputs.document(), inputs.agency(), inputs.content(),
                    inputs.classification(), inputs.restrictionContext());
        } catch (RuntimeException failure) {
            // Includes provider adapters that throw instead of returning the existing UNAVAILABLE result.
            result = generator.unavailable("Urgency assessment is temporarily unavailable.");
        }
        boolean unavailable = result.status() == AssessmentStatus.UNAVAILABLE;
        boolean requiresReview = result.requiresHumanReview()
                || (!unavailable && result.confidence() < properties.reviewConfidenceThreshold());
        ImpactAssessment saved = new ImpactAssessment(profile.getUserId(), policyId, profile.getVersion(),
                unavailable ? AssessmentStatus.UNAVAILABLE : AssessmentStatus.COMPLETED,
                unavailable ? null : result.urgency(), unavailable ? null : result.confidence(),
                result.summary(), result.reasons(), result.recommendedActions(), requiresReview,
                requiresReview ? AssessmentReviewStatus.PENDING_REVIEW : AssessmentReviewStatus.NOT_REQUIRED,
                result.provider(), result.model());
        try {
            write.executeWithoutResult(tx -> results.saveAndFlush(saved));
        } catch (DataIntegrityViolationException conflict) {
            // The losing transaction has already rolled back. The unique key also protects multiple app instances.
            if (!alreadySaved(profile, policyId)) throw conflict;
        }
    }

    private boolean alreadySaved(UserProfile profile, Long policyId) {
        return results.findByUserIdAndPolicyIdAndProfileVersion(profile.getUserId(), policyId,
                profile.getVersion()).isPresent();
    }

    private record Inputs(FederalRegisterDocument document, String agency, String content,
            VisaClassification classification, String restrictionContext, List<UserProfile> profiles) {}
}
