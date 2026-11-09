package mygrant.anomalies;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import mygrant.common.Confidence;
import mygrant.rules.MatchMethod;
import mygrant.rules.RuleLinkFlaggedEvent;

class AnomalyDetectorTests {
    @Test void uncertainRuleLinkIsPersisted() {
        AnomalyRepository repo = Mockito.mock(AnomalyRepository.class);
        new RuleLinkAnomalyListener(repo).flagged(new RuleLinkFlaggedEvent(5L, 7L, MatchMethod.TITLE, Confidence.LOW, "Title-only match"));
        ArgumentCaptor<Anomaly> saved = ArgumentCaptor.forClass(Anomaly.class);
        verify(repo).save(saved.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getValue().getType()).isEqualTo(AnomalyType.UNCERTAIN_RULE_LINK);
    }

    @Test void conflictingDatesArePersisted() {
        AnomalyRepository repo = Mockito.mock(AnomalyRepository.class);
        new EffectiveDateConflictListener(repo).record(new EffectiveDateConflictEvent(5L, 7L,
                java.time.LocalDate.of(2026, 9, 15), java.time.LocalDate.of(2026, 11, 1), "Different dates"));
        ArgumentCaptor<Anomaly> saved = ArgumentCaptor.forClass(Anomaly.class);
        verify(repo).save(saved.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getValue().getType()).isEqualTo(AnomalyType.EFFECTIVE_DATE_CONFLICT);
    }
}
