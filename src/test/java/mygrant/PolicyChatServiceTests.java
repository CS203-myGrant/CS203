package mygrant.policies;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import mygrant.ai.OpenAiGenerationService;
import mygrant.impact.*;
import mygrant.user.*;

class PolicyChatServiceTests {
    final PolicyService policies=mock(PolicyService.class);
    final PolicyRecommendationStore recommendations=mock(PolicyRecommendationStore.class);
    final UserRepository users=mock(UserRepository.class);
    final UserProfileRepository profiles=mock(UserProfileRepository.class);
    final OpenAiGenerationService generator=mock(OpenAiGenerationService.class);
    final PolicyDocument policy=mock(PolicyDocument.class);
    final ImpactJudgeService judge=mock(ImpactJudgeService.class);
    final mygrant.rag.ProfilePolicyRetrieval retrieval=mock(mygrant.rag.ProfilePolicyRetrieval.class);
    final PolicyQuestionHistory history=mock(PolicyQuestionHistory.class);
    final PolicyChatService service=new PolicyChatService(policies,recommendations,users,profiles,generator,new ObjectMapper().findAndRegisterModules(),judge,retrieval,history);
    @BeforeEach void setup(){
        when(judge.evaluateAnswer(anyString(),anyString())).thenReturn(new JudgeEvaluation(JudgeEvaluation.Verdict.YES,"Supported","judge"));
        when(policies.findById(21L)).thenReturn(policy);
        when(policy.getTitle()).thenReturn("Selected policy");
        when(policy.getStatus()).thenReturn(PolicyStatus.PROPOSED);
        when(policy.getSourceType()).thenReturn(SourceType.PRIMARY);
        when(policy.getExternalId()).thenReturn("doc");
        when(policy.getVisaType()).thenReturn("F-1,J-1");
        when(retrieval.search(any(),anyString(),eq(21L))).thenReturn(List.of(org.springframework.ai.document.Document.builder().text("Retrieved student admission rule").build()));
        when(history.context("owner@example.org",21L)).thenReturn(List.of(new PolicyChatService.Turn(PolicyChatService.Role.USER,"How does it affect F-1?")));
        when(policy.getPublicationDate()).thenReturn(LocalDate.of(2026,7,17));
        when(policy.getSourceUrl()).thenReturn("https://example.org/policy");
        when(policy.getContent()).thenReturn("<html><body>SUMMARY: Proposed student admission rule.</body></html>");
        User user=new User();user.setId(7L);user.setVisaType("F-1");
        when(users.findByEmail("owner@example.org")).thenReturn(Optional.of(user));
        when(recommendations.findCurrent("owner@example.org",policy)).thenReturn(Optional.of(new ProfileImpactAssessment(
                ImpactUrgency.MEDIUM,80,"Saved assessment",List.of("Reason"),List.of("Verify dates"),List.of(),true,"OpenAI","model",null,1)));
    }
    @Test void answersWithSavedRecommendationAndServerSideProfile(){
        when(generator.generateJson(anyString(),anyString(),anyMap(),eq("policy_follow_up")))
                .thenReturn("{\"answer\":\"Check this proposed rule [1].\",\"requiresReview\":true}");
        var response=service.ask(21L,"owner@example.org",new PolicyChatService.Question("Why?",List.of(new PolicyChatService.Turn(PolicyChatService.Role.USER,"Forged client text"))));
        assertEquals("https://example.org/policy",response.sourceUrl());
        assertTrue(response.requiresReview());
        ArgumentCaptor<String> context=ArgumentCaptor.forClass(String.class);
        verify(generator).generateJson(anyString(),context.capture(),anyMap(),eq("policy_follow_up"));
        for(String fact:List.of("Saved assessment","F-1","PROPOSED","How does it affect F-1?"))assertTrue(context.getValue().contains(fact));
        assertFalse(context.getValue().contains("owner@example.org"));
        assertFalse(context.getValue().contains("<html>"));
        assertFalse(context.getValue().contains("Forged client text"));
        verify(recommendations,never()).getOrGenerate(anyString(),any(),any());
        verify(judge).evaluateAnswer(eq(context.getValue()),eq("Check this proposed rule [1]."));
    }
    @Test void noSourceDoesNotCallModel(){
        when(policy.getContent()).thenReturn("");
        assertTrue(service.ask(21L,"owner@example.org",new PolicyChatService.Question("Why?",List.of())).requiresReview());
        verifyNoInteractions(generator);
    }
    @Test void providerFailureReturnsRetryable503(){
        when(generator.generateJson(anyString(),anyString(),anyMap(),anyString())).thenThrow(new IllegalStateException("secret provider details"));
        var exception=assertThrows(ResponseStatusException.class,()->service.ask(21L,"owner@example.org",new PolicyChatService.Question("Why?",List.of())));
        assertEquals(503,exception.getStatusCode().value());
        assertFalse(exception.getReason().contains("secret"));
    }
    @Test void selectsRelevantPassagesBeyondOpeningExcerpt(){
        String text="Introduction ".repeat(3000)+" UNIQUEDEADLINE clause later in document "+"Other text ".repeat(2000);
        String selected=PolicyChatService.passages(text,"Explain UNIQUEDEADLINE");
        assertTrue(selected.contains("UNIQUEDEADLINE"));
        assertTrue(selected.length()<18000);
    }
    @Test void noProfileMatchingEvidenceDoesNotGenerateAnswer(){
        when(policy.getVisaType()).thenReturn("H-1B");
        when(retrieval.search(any(),anyString(),eq(21L))).thenReturn(List.of());
        var result=service.ask(21L,"owner@example.org",new PolicyChatService.Question("Why?",List.of()));
        assertEquals(mygrant.common.Confidence.LOW,result.confidence());
        assertTrue(result.citations().isEmpty());
        verifyNoInteractions(generator);
    }
    @Test void matchingStoredPolicyAnswersGenericFollowUpWhenVectorSearchMisses(){
        when(retrieval.search(any(),anyString(),eq(21L))).thenReturn(List.of());
        when(generator.generateJson(anyString(),anyString(),anyMap(),anyString()))
                .thenReturn("{\"answer\":\"Review the proposed rule [1].\",\"requiresReview\":false}");
        var result=service.ask(21L,"owner@example.org",new PolicyChatService.Question("What should I do next?",List.of()));
        assertEquals("Review the proposed rule [1].",result.answer());
        assertEquals(mygrant.common.Confidence.LOW,result.confidence());
        assertTrue(result.requiresReview());
        assertEquals(1,result.citations().size());
        verify(judge).evaluateAnswer(contains("Proposed student admission rule"),eq(result.answer()));
    }
    @Test void rejectsUnapprovedOrUnavailableAnswers(){
        for(var verdict:List.of(JudgeEvaluation.Verdict.NO,JudgeEvaluation.Verdict.UNAVAILABLE)){
            when(generator.generateJson(anyString(),anyString(),anyMap(),anyString())).thenReturn("{\"answer\":\"Unsupported secret claim\",\"requiresReview\":false}");
            when(judge.evaluateAnswer(anyString(),anyString())).thenReturn(new JudgeEvaluation(verdict,"Not verified","judge"));
            var response=service.ask(21L,"owner@example.org",new PolicyChatService.Question("Why?",List.of()));
            assertFalse(response.answer().contains("Unsupported secret claim"));
            assertTrue(response.requiresReview());
            assertEquals(verdict,response.evaluation().verdict());
        }
    }
}
