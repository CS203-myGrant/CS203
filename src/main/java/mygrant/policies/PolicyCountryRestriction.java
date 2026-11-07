package mygrant.policies;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** One country rule, with the source section and exemption wording kept verbatim. */
@Embeddable
public class PolicyCountryRestriction {
    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;
    @Column(name = "restriction_scope", nullable = false, length = 20)
    private String scope;
    @Column(name = "source_reference", nullable = false, length = 2048)
    private String sourceReference;
    @Column(name = "exemption_wording", nullable = false, columnDefinition = "TEXT")
    private String exemptionWording;

    protected PolicyCountryRestriction() {}
    public PolicyCountryRestriction(String countryCode, RestrictionScope scope,
            String sourceReference, String exemptionWording) {
        this.countryCode = CountryIdentifiers.normalize(countryCode);
        this.scope = scope.name();
        this.sourceReference = sourceReference;
        this.exemptionWording = exemptionWording == null ? "" : exemptionWording;
    }
    public String getCountryCode() { return countryCode; }
    public RestrictionScope getScope() { return RestrictionScope.valueOf(scope); }
    public String getSourceReference() { return sourceReference; }
    public String getExemptionWording() { return exemptionWording; }
}
