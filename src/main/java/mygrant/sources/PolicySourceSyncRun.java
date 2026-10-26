package mygrant.sources;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "policy_source_sync_runs")
public class PolicySourceSyncRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private PolicySource source;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "documents_fetched", nullable = false)
    private int documentsFetched;

    @Column(name = "chunks_vectorised", nullable = false)
    private int chunksVectorised;

    @Column(nullable = false)
    private boolean succeeded;

    @Column(columnDefinition = "TEXT")
    private String error;

    protected PolicySourceSyncRun() {
    }

    public PolicySourceSyncRun(PolicySource source, Instant startedAt) {
        this.source = source;
        this.startedAt = startedAt;
    }

    public void complete(Instant finishedAt, int documentsFetched, int chunksVectorised) {
        this.finishedAt = finishedAt;
        this.documentsFetched = documentsFetched;
        this.chunksVectorised = chunksVectorised;
        this.succeeded = true;
    }

    public void fail(Instant finishedAt, String error) {
        this.finishedAt = finishedAt;
        this.error = error;
        this.succeeded = false;
    }

    public Long getId() { return id; }
    public PolicySource getSource() { return source; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public int getDocumentsFetched() { return documentsFetched; }
    public int getChunksVectorised() { return chunksVectorised; }
    public boolean isSucceeded() { return succeeded; }
    public String getError() { return error; }
}
