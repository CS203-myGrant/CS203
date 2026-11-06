package mygrant.rules;

import java.time.LocalDate;

import mygrant.policies.PolicyDocument;
import mygrant.policies.SourceType;

/** Where a history entry came from. policyId is null once the document has been deleted. */
public record RuleSource(Long policyId, String externalId, SourceType sourceType, String url,
        String title, String agency, LocalDate publishedOn) {

    public static RuleSource of(PolicyDocument document) {
        return new RuleSource(document.getId(), document.getExternalId(), document.getSourceType(),
                document.getSourceUrl(), document.getTitle(), document.getAgency(), document.getPublicationDate());
    }
}
