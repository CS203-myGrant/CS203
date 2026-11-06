package mygrant.policies;

import java.time.LocalDate;
import java.util.List;

/**
 * Published once when a new policy has been stored and indexed. Carries the full policy
 * text rather than the excerpt that was indexed for retrieval, so impact rules can find
 * changes described past the opening summary.
 *
 * @param ruleChanged true when the policy also moved its rule (CS203-86): the PolicyChangedEvent
 *     alert, which says what moved, is then the only alert for it
 */
public record PolicyIndexedEvent(
        Long policyId,
        String title,
        List<String> visaTypes,
        LocalDate effectiveDate,
        String content,
        LocalDate publicationDate,
        boolean ruleChanged
) {
    public PolicyIndexedEvent(Long policyId, String title, List<String> visaTypes,
            LocalDate effectiveDate, String content, LocalDate publicationDate) {
        this(policyId, title, visaTypes, effectiveDate, content, publicationDate, false);
    }

    /** An event without a publication date, which counts as already published. */
    public PolicyIndexedEvent(Long policyId, String title, List<String> visaTypes,
            LocalDate effectiveDate, String content) {
        this(policyId, title, visaTypes, effectiveDate, content, null);
    }

    public static PolicyIndexedEvent from(PolicyDocument policy) {
        return from(policy, false);
    }

    public static PolicyIndexedEvent from(PolicyDocument policy, boolean ruleChanged) {
        return new PolicyIndexedEvent(policy.getId(), policy.getTitle(),
                List.of(policy.getVisaType().split(",")), policy.getEffectiveDate(),
                policy.getContent(), policy.getPublicationDate(), ruleChanged);
    }
}
