package mygrant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import mygrant.impact.*;
import mygrant.policies.*;
import mygrant.user.*;

class PolicyRecommendationStoreTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final UserRepository users = mock(UserRepository.class);
    final UserProfileRepository profiles = mock(UserProfileRepository.class);
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final User user = new User();
    final UserProfile profile = UserProfile.fromUser(user);
    final PolicyDocument policy = new PolicyDocument("doc", "Policy", "Agency", "F-1", PolicyStatus.FINAL,
            SourceType.PRIMARY, LocalDate.of(2026, 7, 17), null, "https://example.org", "Text", "hash");
    final ProfileImpactAssessment result = new ProfileImpactAssessment(ImpactUrgency.LOW, 90, "Summary",
            List.of("Reason"), List.of("Next step"), false, "OpenAI", "model", null, 1);
    String savedHash;
    String savedJson;

    PolicyRecommendationStore store() { return new PolicyRecommendationStore(jdbc, mapper, users, profiles); }

    @BeforeEach
    void setup() {
        user.setId(1L);
        profile.setUserId(1L);
        profile.setVisaType("F-1");
        ReflectionTestUtils.setField(policy, "id", 21L);
        when(users.findByEmail("user")).thenReturn(Optional.of(user));
        when(profiles.findById(1L)).thenReturn(Optional.of(profile));
        when(jdbc.queryForList(anyString(), eq(String.class), eq(1L), eq(21L), anyString()))
                .thenAnswer(call -> call.getArgument(4).equals(savedHash) ? List.of(savedJson) : List.of());
        when(jdbc.update(anyString(), eq(1L), eq(21L), anyString(), anyString())).thenAnswer(call -> {
            savedHash = call.getArgument(3);
            savedJson = call.getArgument(4);
            return 1;
        });
    }

    @Test
    void persistsResultAndReusesItFromAnotherServiceInstance() {
        assertEquals(result, store().getOrGenerate("user", policy, () -> result));
        assertEquals(result, store().getOrGenerate("user", policy, () -> { throw new AssertionError("AI called twice"); }));
        verify(jdbc, times(1)).update(anyString(), eq(1L), eq(21L), anyString(), anyString());
    }

    @Test
    void regeneratesAfterProfileOrPolicyChanges() {
        @SuppressWarnings("unchecked") Supplier<ProfileImpactAssessment> generate = mock(Supplier.class);
        when(generate.get()).thenReturn(result);
        store().getOrGenerate("user", policy, generate);
        profile.setVisaType("J-1");
        store().getOrGenerate("user", policy, generate);
        ReflectionTestUtils.setField(policy, "content", "Updated source");
        store().getOrGenerate("user", policy, generate);
        verify(generate, times(3)).get();
    }

    @Test
    void temporaryFailureIsNotSaved() {
        var unavailable = new ProfileImpactAssessment(ImpactUrgency.UNAVAILABLE, 0, "Try later",
                List.of(), List.of(), true, "OpenAI", "model", null, 0);
        store().getOrGenerate("user", policy, () -> unavailable);
        assertNull(savedJson);
        assertEquals(result, store().getOrGenerate("user", policy, () -> result));
    }

    @Test
    void anotherUserCannotReuseFirstUsersAssessment() {
        store().getOrGenerate("user", policy, () -> result);
        var other = new User();
        other.setId(2L);
        when(users.findByEmail("other")).thenReturn(Optional.of(other));
        boolean[] generated = {false};
        store().getOrGenerate("other", policy, () -> { generated[0] = true; return result; });
        assertTrue(generated[0]);
        verify(jdbc).queryForList(anyString(), eq(String.class), eq(2L), eq(21L), anyString());
    }
}
