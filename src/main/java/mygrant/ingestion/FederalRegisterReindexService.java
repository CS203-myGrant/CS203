package mygrant.ingestion;

import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import mygrant.policies.PolicyDocument;
import mygrant.policies.PolicyDocumentRepository;

/** Repairs Federal Register documents indexed before the full text was searchable. */
@Service
public class FederalRegisterReindexService {

    private final JdbcTemplate jdbc;
    private final PolicyDocumentRepository policies;
    private final PolicyIngestionService ingestion;
    private final TransactionTemplate transactions;

    public FederalRegisterReindexService(JdbcTemplate jdbc, PolicyDocumentRepository policies,
            PolicyIngestionService ingestion, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.policies = policies;
        this.ingestion = ingestion;
        this.transactions = transactions;
    }

    /** Replaces old chunks from saved text, one document per transaction. Safe to rerun. */
    public ReindexResult rebuild() {
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM policy_documents
                WHERE source_url LIKE 'https://www.federalregister.gov/%'
                  AND length(content) > 6000 AND indexed_at IS NOT NULL
                ORDER BY id
                """, Long.class);
        int chunks = 0;
        for (Long id : ids) {
            chunks += transactions.execute(status -> rebuildOne(id));
        }
        return new ReindexResult(ids.size(), chunks);
    }

    private int rebuildOne(Long id) {
        PolicyDocument policy = policies.findById(id).orElseThrow();
        jdbc.update("DELETE FROM policy_chunks WHERE metadata->>'policyId' = ?", id.toString());
        int chunks = ingestion.indexChunks(policy, policy.getContent());
        policy.markIndexed(Instant.now());
        policies.save(policy);
        return chunks;
    }

    public record ReindexResult(int documentsReindexed, int chunksIndexed) {}
}
