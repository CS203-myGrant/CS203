package mygrant.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import mygrant.policies.PolicyDocument;
import mygrant.policies.PolicyDocumentRepository;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;

@ExtendWith(MockitoExtension.class)
class FederalRegisterReindexServiceTests {
    @Mock JdbcTemplate jdbc;
    @Mock PolicyDocumentRepository policies;
    @Mock PolicyIngestionService ingestion;
    @Mock TransactionTemplate transactions;
    @Mock TransactionStatus status;

    @Test
    @SuppressWarnings("unchecked")
    void replacesSavedChunksWithoutReimportingAndCanBeRerun() {
        String fullText = "Original rule text. ".repeat(400) + "Late exemption.";
        PolicyDocument policy = new PolicyDocument("2026-12345", "Student rule", "DHS", "F-1",
                PolicyStatus.FINAL, SourceType.PRIMARY, LocalDate.of(2026, 1, 1), null,
                "https://www.federalregister.gov/d/2026-12345", fullText, "hash");
        ReflectionTestUtils.setField(policy, "id", 9L);
        when(jdbc.queryForList(any(String.class), eq(Long.class))).thenReturn(List.of(9L));
        when(policies.findById(9L)).thenReturn(Optional.of(policy));
        when(ingestion.indexChunks(policy, fullText)).thenReturn(7);
        when(transactions.execute(any())).thenAnswer(call ->
                ((TransactionCallback<Integer>) call.getArgument(0)).doInTransaction(status));

        var service = new FederalRegisterReindexService(jdbc, policies, ingestion, transactions);
        assertThat(service.rebuild()).isEqualTo(new FederalRegisterReindexService.ReindexResult(1, 7));
        assertThat(service.rebuild()).isEqualTo(new FederalRegisterReindexService.ReindexResult(1, 7));

        verify(jdbc, times(2)).update("DELETE FROM policy_chunks WHERE metadata->>'policyId' = ?", "9");
        verify(ingestion, times(2)).indexChunks(policy, fullText);
        verify(policies, times(2)).save(policy);
        assertThat(policy.getIndexedAt()).isNotNull();
    }
}
