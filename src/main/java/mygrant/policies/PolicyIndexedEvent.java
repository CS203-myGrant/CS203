package mygrant.policies;

import java.time.LocalDate;
import java.util.List;

/**
 * Published once when a new policy has been stored and indexed. Carries the full policy
 * text rather than the excerpt that was indexed for retrieval, so impact rules can find
 * changes described past the opening summary.
 */
public record PolicyIndexedEvent(
        Long policyId,
        String title,
        List<String> visaTypes,
        LocalDate effectiveDate,
        String content,
        LocalDate publicationDate
) {
    /** An event without a publication date, which counts as already published. */
    public PolicyIndexedEvent(Long policyId, String title, List<String> visaTypes,
            LocalDate effectiveDate, String content) {
        this(policyId, title, visaTypes, effectiveDate, content, null);
    }

    public static PolicyIndexedEvent from(PolicyDocument policy) {
        return new PolicyIndexedEvent(policy.getId(), policy.getTitle(),
                List.of(policy.getVisaType().split(",")), policy.getEffectiveDate(),
                policy.getContent(), policy.getPublicationDate());
    }
}
