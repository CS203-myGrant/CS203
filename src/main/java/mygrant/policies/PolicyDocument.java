package mygrant.policies;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Persisted source policy, including its complete text and indexing status. */
@Entity
@Table(name = "policy_documents")
public class PolicyDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, unique = true)
    private String externalId;

    @Column(nullable = false, length = 1000)
    private String title;

    @Column(nullable = false)
    private String agency;

    @Column(name = "visa_type", nullable = false, length = 50)
    private String visaType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PolicyStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    private SourceType sourceType;

    @Column(name = "publication_date", nullable = false)
    private LocalDate publicationDate;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "source_url", nullable = false, length = 2048)
    private String sourceUrl;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "policy_affected_countries", joinColumns = @JoinColumn(name = "policy_id"))
    @Column(name = "country_code", nullable = false, length = 2)
    private Set<String> affectedCountries = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "policy_restriction_categories", joinColumns = @JoinColumn(name = "policy_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 40)
    private Set<RestrictionCategory> restrictionCategories = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "policy_source_references", joinColumns = @JoinColumn(name = "policy_id"))
    @OrderColumn(name = "reference_order")
    @Column(name = "source_reference", nullable = false, length = 2048)
    private List<String> sourceReferences = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "policy_country_restrictions", joinColumns = @JoinColumn(name = "policy_id"))
    @OrderColumn(name = "rule_order")
    private List<PolicyCountryRestriction> countryRestrictions = new ArrayList<>();

    @Column(name = "extraction_review_required", nullable = false)
    private boolean extractionReviewRequired;

    @Column(name = "special_document_holder_rule", nullable = false)
    private boolean specialDocumentHolderRule;

    @Column(name = "indexed_at")
    private Instant indexedAt;

    protected PolicyDocument() {
    }

    public PolicyDocument(String externalId, String title, String agency, String visaType,
            PolicyStatus status, SourceType sourceType, LocalDate publicationDate,
            LocalDate effectiveDate, String sourceUrl, String content, String contentHash) {
        this(externalId, title, agency, visaType, status, sourceType, publicationDate,
                effectiveDate, sourceUrl, content, contentHash, Set.of(), Set.of(), List.of(), List.of());
    }

    public PolicyDocument(String externalId, String title, String agency, String visaType,
            PolicyStatus status, SourceType sourceType, LocalDate publicationDate,
            LocalDate effectiveDate, String sourceUrl, String content, String contentHash,
            Set<String> affectedCountries, Set<RestrictionCategory> restrictionCategories,
            List<String> sourceReferences) {
        this(externalId, title, agency, visaType, status, sourceType, publicationDate, effectiveDate,
                sourceUrl, content, contentHash, affectedCountries, restrictionCategories, sourceReferences, List.of(), false);
    }

    public PolicyDocument(String externalId, String title, String agency, String visaType,
            PolicyStatus status, SourceType sourceType, LocalDate publicationDate,
            LocalDate effectiveDate, String sourceUrl, String content, String contentHash,
            Set<String> affectedCountries, Set<RestrictionCategory> restrictionCategories,
            List<String> sourceReferences, List<PolicyCountryRestriction> countryRestrictions) {
        this(externalId, title, agency, visaType, status, sourceType, publicationDate, effectiveDate,
                sourceUrl, content, contentHash, affectedCountries, restrictionCategories,
                sourceReferences, countryRestrictions, false, false);
    }

    public PolicyDocument(String externalId, String title, String agency, String visaType,
            PolicyStatus status, SourceType sourceType, LocalDate publicationDate,
            LocalDate effectiveDate, String sourceUrl, String content, String contentHash,
            Set<String> affectedCountries, Set<RestrictionCategory> restrictionCategories,
            List<String> sourceReferences, List<PolicyCountryRestriction> countryRestrictions,
            boolean extractionReviewRequired) {
        this(externalId, title, agency, visaType, status, sourceType, publicationDate, effectiveDate,
                sourceUrl, content, contentHash, affectedCountries, restrictionCategories, sourceReferences,
                countryRestrictions, extractionReviewRequired, false);
    }

    public PolicyDocument(String externalId, String title, String agency, String visaType,
            PolicyStatus status, SourceType sourceType, LocalDate publicationDate,
            LocalDate effectiveDate, String sourceUrl, String content, String contentHash,
            Set<String> affectedCountries, Set<RestrictionCategory> restrictionCategories,
            List<String> sourceReferences, List<PolicyCountryRestriction> countryRestrictions,
            boolean extractionReviewRequired, boolean specialDocumentHolderRule) {
        this.externalId = externalId;
        this.title = title;
        this.agency = agency;
        this.visaType = visaType;
        this.status = status;
        this.sourceType = sourceType;
        this.publicationDate = publicationDate;
        this.effectiveDate = effectiveDate;
        this.sourceUrl = sourceUrl;
        this.content = content;
        this.contentHash = contentHash;
        this.affectedCountries = new LinkedHashSet<>(affectedCountries);
        this.restrictionCategories = new LinkedHashSet<>(restrictionCategories);
        this.sourceReferences = new ArrayList<>(sourceReferences);
        this.countryRestrictions = new ArrayList<>(countryRestrictions);
        this.extractionReviewRequired = extractionReviewRequired;
        this.specialDocumentHolderRule = specialDocumentHolderRule;
    }

    public Long getId() { return id; }
    public String getExternalId() { return externalId; }
    public String getTitle() { return title; }
    public String getAgency() { return agency; }
    public String getVisaType() { return visaType; }
    public PolicyStatus getStatus() { return status; }
    public SourceType getSourceType() { return sourceType; }
    public LocalDate getPublicationDate() { return publicationDate; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public String getSourceUrl() { return sourceUrl; }
    public String getContent() { return content; }
    public String getContentHash() { return contentHash; }
    // Sorted, not Set.copyOf: its iteration order changes on every JVM start, and the saved
    // assessment's fingerprint serialises these sets, so a restart would discard the saved result.
    public Set<String> getAffectedCountries() {
        return java.util.Collections.unmodifiableSortedSet(new java.util.TreeSet<>(affectedCountries));
    }
    public Set<RestrictionCategory> getRestrictionCategories() {
        return java.util.Collections.unmodifiableSortedSet(new java.util.TreeSet<>(restrictionCategories));
    }
    public List<String> getSourceReferences() { return List.copyOf(sourceReferences); }
    public List<PolicyCountryRestriction> getCountryRestrictions() { return List.copyOf(countryRestrictions); }
    public boolean isExtractionReviewRequired() { return extractionReviewRequired; }
    public boolean hasSpecialDocumentHolderRule() { return specialDocumentHolderRule; }
    public Instant getIndexedAt() { return indexedAt; }

    public void markIndexed(Instant indexedAt) {
        this.indexedAt = indexedAt;
    }
}
