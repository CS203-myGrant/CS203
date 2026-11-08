package mygrant.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import mygrant.impact.ProfileImpactAssessment;
import mygrant.impact.ProfileImpactAssessmentService;
import mygrant.ingestion.classification.VisaClassification;
import mygrant.ingestion.federalregister.FederalRegisterDocument;
import mygrant.policies.PolicyDocument;
import mygrant.policies.PolicyDocumentRepository;
import mygrant.policies.PolicyRecommendationStore;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;

/** Reassessment goes through the gated recommendation store, one user at a time. */
class RuleReassessmentListenerTest {

    private final RuleReassessmentScope scope = mock(RuleReassessmentScope.class);
    private final PolicyDocumentRepository policies = mock(PolicyDocumentRepository.class);
    private final PolicyRecommendationStore store = mock(PolicyRecommendationStore.class);
    private final ProfileImpactAssessmentService assessments = mock(ProfileImpactAssessmentService.class);
    private final RuleReassessmentListener listener = new RuleReassessmentListener(scope, policies, store, assessments);

    @Test
    void reassessesEachUserInScopeThroughTheStoreAndSkipsOnlyAFailingOne() {
        PolicyDocument finalRule = new PolicyDocument("2026-14439", RuleFixtures.TITLE, "Homeland Security Department",
                "F-1,J-1", PolicyStatus.FINAL, SourceType.PRIMARY, LocalDate.of(2026, 7, 17),
                LocalDate.of(2026, 9, 15), "https://www.federalregister.gov/d/2026-14439", "Final rule text.", "hash");
        ReflectionTestUtils.setField(finalRule, "id", 5L);
        when(policies.findById(5L)).thenReturn(Optional.of(finalRule));
        PolicyChangedEvent change = change(5L);
        when(scope.usersToReassess(change)).thenReturn(List.of("first@example.com", "second@example.com"));
        when(store.getOrGenerate(eq("first@example.com"), same(finalRule), any()))
                .thenThrow(new IllegalStateException("model unavailable"));

        listener.onPolicyChanged(change);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Supplier<ProfileImpactAssessment>> generate = ArgumentCaptor.forClass(Supplier.class);
        verify(store).getOrGenerate(eq("second@example.com"), same(finalRule), generate.capture());
        generate.getValue().get();
        ArgumentCaptor<FederalRegisterDocument> document = ArgumentCaptor.forClass(FederalRegisterDocument.class);
        ArgumentCaptor<VisaClassification> classification = ArgumentCaptor.forClass(VisaClassification.class);
        verify(assessments).assess(eq("second@example.com"), same(finalRule), document.capture(),
                classification.capture());
        assertThat(document.getValue().documentNumber()).isEqualTo("2026-14439");
        assertThat(classification.getValue().visaTypes()).containsExactly("F-1", "J-1");
    }

    @Test
    void aDeletedSourceDocumentIsNotReassessed() {
        when(policies.findById(5L)).thenReturn(Optional.empty());

        listener.onPolicyChanged(change(5L));

        verifyNoInteractions(scope, store);
        verify(assessments, never()).assess(any(), any(PolicyDocument.class), any(FederalRegisterDocument.class),
                any(VisaClassification.class));
    }

    private static PolicyChangedEvent change(long sourceId) {
        RuleState before = new RuleState(PolicyStatus.PROPOSED, null, false);
        RuleState after = new RuleState(PolicyStatus.FINAL, LocalDate.of(2026, 9, 15), false);
        return new PolicyChangedEvent(9L, 1L, RuleFixtures.TITLE, before, after, Set.of(RuleChange.STAGE),
                new RuleSource(sourceId, "2026-14439", SourceType.PRIMARY, "https://www.federalregister.gov/d/2026-14439",
                        RuleFixtures.TITLE, "Homeland Security Department", LocalDate.of(2026, 7, 17)),
                List.of("F-1", "J-1"), RuleFixtures.TITLE, "Final rule text.");
    }
}
