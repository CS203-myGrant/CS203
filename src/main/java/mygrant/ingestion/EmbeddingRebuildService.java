package mygrant.ingestion;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Recomputes every stored chunk's vector with the configured embedding model. Vectors from
 * different models are not comparable, so after the model changes, retrieval only works
 * once old chunks are re-embedded. The chunk text and citation metadata are kept, so no
 * policy needs re-importing and no alert is touched.
 */
@Service
public class EmbeddingRebuildService {

    // Small enough to stay well under OpenAI's per-request input limit for chunks of
    // about 800 tokens each.
    static final int BATCH_SIZE = 50;

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;

    public EmbeddingRebuildService(JdbcTemplate jdbc, EmbeddingModel embeddingModel) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Re-embeds every chunk, one batch per request. Each batch is saved as soon as it is
     * embedded, so a failure part way leaves earlier batches done and a rerun redoes all.
     *
     * @return how many chunks were re-embedded
     */
    public int rebuild() {
        List<Chunk> chunks = jdbc.query("SELECT id, content FROM policy_chunks ORDER BY id",
                (rs, row) -> new Chunk(rs.getObject("id", UUID.class), rs.getString("content")));
        for (int start = 0; start < chunks.size(); start += BATCH_SIZE) {
            List<Chunk> batch = chunks.subList(start, Math.min(start + BATCH_SIZE, chunks.size()));
            List<float[]> vectors = embeddingModel.embed(batch.stream().map(Chunk::content).toList());
            jdbc.batchUpdate("UPDATE policy_chunks SET embedding = ?::vector WHERE id = ?",
                    IntStream.range(0, batch.size())
                            .mapToObj(i -> new Object[] {toVectorLiteral(vectors.get(i)), batch.get(i).id()})
                            .toList());
        }
        return chunks.size();
    }

    // pgvector reads a vector from text written as [0.1,0.2,...].
    static String toVectorLiteral(float[] vector) {
        return IntStream.range(0, vector.length)
                .mapToObj(i -> Float.toString(vector[i]))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private record Chunk(UUID id, String content) {
    }
}
