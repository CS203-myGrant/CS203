package mygrant.anomalies;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import mygrant.policies.PolicyDocumentRepository;
import mygrant.rules.PolicyRuleStore;

@ExtendWith(MockitoExtension.class)
class AnomalyServiceTests {
    @Mock AnomalyRepository anomalies;
    @Mock PolicyRuleStore rules;
    @Mock PolicyDocumentRepository policies;
    @Mock ApplicationEventPublisher events;

    @Test
    void createsOneOpenSourceFailureAnomalyPerDocumentAndType() {
        when(anomalies.findFirstByTypeAndSourceIdAndStatusAndDetailsStartingWithOrderByCreatedAtDesc(
                eq(AnomalyType.MALFORMED_SOURCE_DATA), eq(8L), eq(AnomalyStatus.OPEN), eq("Document 2026-1: ")))
                .thenReturn(Optional.empty());

        service().recordSourceFailure(8L, "2026-1", "Missing publication date", new IllegalArgumentException("bad"));

        ArgumentCaptor<Anomaly> saved = ArgumentCaptor.forClass(Anomaly.class);
        verify(anomalies).save(saved.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getValue().getType()).isEqualTo(AnomalyType.MALFORMED_SOURCE_DATA);
        org.assertj.core.api.Assertions.assertThat(saved.getValue().getDetails()).contains("2026-1");
    }

    @Test
    void doesNotDuplicateAnExistingOpenSourceFailure() {
        Anomaly existing = new Anomaly(AnomalyType.CLASSIFICATION_FAILED, null, null, 8L, "Document 2026-1: old");
        when(anomalies.findFirstByTypeAndSourceIdAndStatusAndDetailsStartingWithOrderByCreatedAtDesc(
                eq(AnomalyType.CLASSIFICATION_FAILED), eq(8L), eq(AnomalyStatus.OPEN), anyString()))
                .thenReturn(Optional.of(existing));

        service().recordSourceFailure(8L, "2026-1", "classification failed", new IllegalStateException("classification failed"));

        verify(anomalies, never()).save(any());
    }

    @Test
    void ignoresAnOpenAnomalyWithAnAuditableResolution() {
        Anomaly anomaly = new Anomaly(AnomalyType.MALFORMED_SOURCE_DATA, null, null, 8L, "bad payload");
        when(anomalies.findById(12L)).thenReturn(Optional.of(anomaly));
        when(anomalies.save(anomaly)).thenReturn(anomaly);

        service().resolve(12L, AnomalyStatus.IGNORED, "Known upstream outage", null, "admin@example.com");

        org.assertj.core.api.Assertions.assertThat(anomaly.getStatus()).isEqualTo(AnomalyStatus.IGNORED);
        org.assertj.core.api.Assertions.assertThat(anomaly.getResolvedBy()).isEqualTo("admin@example.com");
        verify(anomalies).save(anomaly);
    }

    @Test
    void rejectsResolvingADateConflictWithoutTheChosenDate() {
        Anomaly anomaly = new Anomaly(AnomalyType.EFFECTIVE_DATE_CONFLICT, 3L, 4L, null, "dates differ");
        when(anomalies.findById(12L)).thenReturn(Optional.of(anomaly));

        assertThatThrownBy(() -> service().resolve(12L, AnomalyStatus.RESOLVED, "verified", null, "admin@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("correctEffectiveDate");
    }

    private AnomalyService service() { return new AnomalyService(anomalies, rules, policies, events); }
}
