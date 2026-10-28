package mygrant.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Verifies stored chunks are re-embedded in batches and written back to their own rows. */
@ExtendWith(MockitoExtension.class)
class EmbeddingRebuildServiceTests {

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private EmbeddingModel embeddingModel;

    @Test
    @SuppressWarnings("unchecked")
    void reembedsEveryChunkInBatchesAndUpdatesItsRow() {
        List<UUID> ids = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < EmbeddingRebuildService.BATCH_SIZE + 1; i++) {
            ids.add(UUID.randomUUID());
            texts.add("chunk " + i);
        }
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any())).thenAnswer(invocation -> {
            RowMapper<Object> mapper = invocation.getArgument(1);
            List<Object> rows = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
                when(rs.getObject("id", UUID.class)).thenReturn(ids.get(i));
                when(rs.getString("content")).thenReturn(texts.get(i));
                rows.add(mapper.mapRow(rs, i));
            }
            return rows;
        });
        when(embeddingModel.embed(org.mockito.ArgumentMatchers.<List<String>>any())).thenAnswer(invocation ->
                ((List<String>) invocation.getArgument(0)).stream()
                        .map(text -> new float[] {text.length(), 0.5f})
                        .toList());

        int rebuilt = new EmbeddingRebuildService(jdbc, embeddingModel).rebuild();

        assertThat(rebuilt).isEqualTo(EmbeddingRebuildService.BATCH_SIZE + 1);
        ArgumentCaptor<List<String>> batches = ArgumentCaptor.forClass(List.class);
        verify(embeddingModel, org.mockito.Mockito.times(2)).embed(batches.capture());
        assertThat(batches.getAllValues().get(0)).hasSize(EmbeddingRebuildService.BATCH_SIZE);
        assertThat(batches.getAllValues().get(1)).containsExactly(texts.get(EmbeddingRebuildService.BATCH_SIZE));

        ArgumentCaptor<List<Object[]>> updates = ArgumentCaptor.forClass(List.class);
        verify(jdbc, org.mockito.Mockito.times(2))
                .batchUpdate(eq("UPDATE policy_chunks SET embedding = ?::vector WHERE id = ?"), updates.capture());
        assertThat(updates.getAllValues().get(0).get(0)).containsExactly("[7.0,0.5]", ids.get(0));
    }

    @Test
    void writesVectorsInPgvectorTextFormat() {
        assertThat(EmbeddingRebuildService.toVectorLiteral(new float[] {0.25f, -1f, 3.5f}))
                .isEqualTo("[0.25,-1.0,3.5]");
    }
}
