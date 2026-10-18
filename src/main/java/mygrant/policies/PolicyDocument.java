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

    @Column(name = "indexed_at")
    private Instant indexedAt;

    protected PolicyDocument() {
    }

    public PolicyDocument(String externalId, String title, String agency, String visaType,
            PolicyStatus status, SourceType sourceType, LocalDate publicationDate,
            LocalDate effectiveDate, String sourceUrl, String content, String contentHash) {
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
    public Instant getIndexedAt() { return indexedAt; }

    public void markIndexed(Instant indexedAt) {
        this.indexedAt = indexedAt;
    }
}
