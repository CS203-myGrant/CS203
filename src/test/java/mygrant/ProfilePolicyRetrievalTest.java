package mygrant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.*;
import mygrant.rag.ProfilePolicyRetrieval;
import mygrant.user.*;

class ProfilePolicyRetrievalTest {
    @Test void filtersBeforeSearchAndRejectsOutOfScopeReturnedChunks(){
        var vectors=mock(VectorStore.class);
        var retrieval=new ProfilePolicyRetrieval(mock(UserRepository.class),mock(UserProfileRepository.class),vectors);
        var user=new User();user.setVisaType("F-1");
        var match=Document.builder().text("Matching rule").metadata(Map.of("visaType","F-1","policyId","21")).build();
        var otherVisa=Document.builder().text("Wrong visa").metadata(Map.of("visaType","H-1B","policyId","21")).build();
        var otherPolicy=Document.builder().text("Wrong policy").metadata(Map.of("visaType","F-1","policyId","22")).build();
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(match,otherVisa,otherPolicy));
        assertEquals(List.of(match),retrieval.search(UserProfile.fromUser(user),"What changed?",21L));
        var query=ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectors).similaritySearch(query.capture());
        assertTrue(query.getValue().getFilterExpression().toString().contains("F-1"));
        assertTrue(query.getValue().getFilterExpression().toString().contains("21"));
    }
    @Test void resolvesCurrentProfileInsteadOfLegacyUserFields(){
        var users=mock(UserRepository.class);var profiles=mock(UserProfileRepository.class);
        var user=new User();user.setId(1L);user.setVisaType("F-1");
        var profile=UserProfile.fromUser(user);profile.setVisaType("H-1B");
        when(users.findByEmail("owner")).thenReturn(Optional.of(user));
        when(profiles.findById(1L)).thenReturn(Optional.of(profile));
        assertEquals("H-1B",new ProfilePolicyRetrieval(users,profiles,mock(VectorStore.class)).profile("owner").getVisaType());
    }
    @Test void malformedProfileDoesNotProduceAnUnfilteredSearch(){
        var vectors=mock(VectorStore.class);var user=new User();user.setVisaType("F-1' || true");
        assertTrue(new ProfilePolicyRetrieval(mock(UserRepository.class),mock(UserProfileRepository.class),vectors)
                .search(UserProfile.fromUser(user),"Question",null).isEmpty());
        verifyNoInteractions(vectors);
    }
}
