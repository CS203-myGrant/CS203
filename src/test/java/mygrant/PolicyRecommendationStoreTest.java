package mygrant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import mygrant.actions.ActionItemService;
import mygrant.impact.*;
import mygrant.policies.*;
import mygrant.user.*;

/**
 * The store against an in-memory stand-in for policy_recommendations, keyed like the real table
 * on (user, policy, fingerprint).
 */
class PolicyRecommendationStoreTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final UserRepository users = mock(UserRepository.class);
    final UserProfileRepository profiles = mock(UserProfileRepository.class);
    final ActionItemService actions = mock(ActionItemService.class);
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final User user = new User();
    final UserProfile profile = UserProfile.empty(1L);
    final PolicyDocument policy = new PolicyDocument("doc", "Policy", "Agency", "F-1", PolicyStatus.FINAL,
            SourceType.PRIMARY, LocalDate.of(2026, 7, 17), null, "https://example.org", "Text", "hash");
    final ProfileImpactAssessment result = new ProfileImpactAssessment(ImpactUrgency.LOW, 90, "Summary",
            List.of("Reason"), List.of("Next step"), List.of(), false, "OpenAI", "model", new JudgeEvaluation(JudgeEvaluation.Verdict.YES,"Supported","judge"), 1);

    /** user:policy:hash → row, as the unique key on the real table. */
    final Map<String, Row> table = new LinkedHashMap<>();
    long nextId = 31;

    static final class Row {
        long id; String json; String status; boolean human;
        Row(long id, String json, String status, boolean human) {
            this.id = id; this.json = json; this.status = status; this.human = human;
        }
    }

    PolicyRecommendationStore store() {
        return new PolicyRecommendationStore(jdbc, mapper, users, profiles, actions, new ImpactReviewPolicy(60));
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        user.setId(1L);
        profile.setUserId(1L);
        profile.setVisaType("F-1");
        ReflectionTestUtils.setField(policy, "id", 21L);
        when(users.findByEmail("user")).thenReturn(Optional.of(user));
        when(profiles.findById(1L)).thenReturn(Optional.of(profile));
        // Reads of one fingerprint's row (current state, or the row that won a conflict).
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any())).thenAnswer(call -> {
            Row row = table.get(key(call.getArgument(2), call.getArgument(3), call.getArgument(4)));
            if (row == null) return List.of();
            ResultSet rs = mock(ResultSet.class);
            when(rs.getLong("id")).thenReturn(row.id);
            when(rs.getLong(1)).thenReturn(row.id);
            when(rs.getString("assessment_json")).thenReturn(row.json);
            when(rs.getString("publication_status")).thenReturn(row.status);
            when(rs.getString(2)).thenReturn(row.status);
            when(rs.getBoolean("reviewed_by_human")).thenReturn(row.human);
            return List.of(((RowMapper<Object>) call.getArgument(1)).mapRow(rs, 0));
        });
        // INSERT ... ON CONFLICT ... RETURNING id: empty when the existing row is kept.
        when(jdbc.queryForList(contains("INSERT INTO policy_recommendations"), eq(Long.class),
                any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            String key = key(call.getArgument(2), call.getArgument(3), call.getArgument(4));
            Row existing = table.get(key);
            boolean replace = call.<String>getArgument(0).contains("DO UPDATE");
            if (existing != null && (existing.human || !replace)) return List.of();
            Row row = existing == null ? new Row(nextId++, null, null, false) : existing;
            row.json = call.getArgument(5);
            row.status = call.getArgument(6);
            table.put(key, row);
            return List.of(row.id);
        });
        when(jdbc.queryForList(contains("publication_status = 'PUBLISHED'"), eq(String.class), any(), any(), any()))
                .thenAnswer(call -> {
                    Row row = table.get(key(call.getArgument(2), call.getArgument(3), call.getArgument(4)));
                    return row != null && "PUBLISHED".equals(row.status) ? List.of(row.json) : List.of();
                });
    }

    static String key(Object user, Object policy, Object hash) {
        return user + ":" + policy + ":" + hash;
    }

    String onlyStatus() {
        return table.values().stream().map(row -> row.status).reduce((a, b) -> b).orElseThrow();
    }

    @Test
    void persistsResultAndReusesItFromAnotherServiceInstance() {
        assertEquals(result, store().getOrGenerate("user", policy, () -> result));
        assertEquals(result, store().getOrGenerate("user", policy, () -> { throw new AssertionError("AI called twice"); }));
        assertEquals(1, table.size());
        assertEquals(result, store().findCurrent("user", policy).orElseThrow());
    }

    @Test
    void onlyPublishedResultsReachTheOwnerAndAHeldDraftWaitsForItsReviewer() {
        store().getOrGenerate("user", policy, () -> result);
        verify(actions).syncForAssessment(31L);
        assertEquals("PUBLISHED", onlyStatus());

        var rejected = new ProfileImpactAssessment(ImpactUrgency.HIGH, 95, "Draft", List.of(), List.of("Draft step"),
                List.of(), true, "OpenAI", "model", new JudgeEvaluation(JudgeEvaluation.Verdict.NO, "Unsupported", "judge"), 1);
        profile.setVisaType("J-1");
        var shown = store().getOrGenerate("user", policy, () -> rejected);
        assertEquals(AssessmentStatus.AWAITING_REVIEW, shown.status());
        assertNull(shown.urgency());
        assertEquals("PENDING_REVIEW", onlyStatus());
        assertTrue(store().findCurrent("user", policy).isEmpty());
        // Opening the page again, even asking for a fresh assessment, does not bypass the reviewer.
        assertEquals(AssessmentStatus.AWAITING_REVIEW, store().getOrGenerate("user", policy,
                () -> { throw new AssertionError("Held draft regenerated"); }, true).status());
    }

    @Test
    void lowConfidenceIsHeldEvenWhenTheJudgePassesIt() {
        var unsure = new ProfileImpactAssessment(ImpactUrgency.MEDIUM, 59, "Unsure", List.of(), List.of("Check"),
                List.of(), false, "OpenAI", "model", new JudgeEvaluation(JudgeEvaluation.Verdict.YES, "Supported", "judge"), 1);
        assertEquals(AssessmentStatus.AWAITING_REVIEW, store().getOrGenerate("user", policy, () -> unsure).status());
        assertEquals("PENDING_REVIEW", onlyStatus());
    }

    @Test
    void confidentVerifiedHighIsPublishedStraightAway() {
        var high = new ProfileImpactAssessment(ImpactUrgency.HIGH, 92, "Act now", List.of(), List.of("Check"),
                List.of(), false, "OpenAI", "model", new JudgeEvaluation(JudgeEvaluation.Verdict.YES, "Supported", "judge"), 1);
        assertEquals(high, store().getOrGenerate("user", policy, () -> high));
        assertEquals("PUBLISHED", onlyStatus());
    }

    @Test
    void humanReviewedRowIsNeverReplacedByAutomatedWrites() throws Exception {
        String hash = store().fingerprint(profile, policy);
        String corrected = mapper.writeValueAsString(new ProfileImpactAssessment(ImpactUrgency.LOW, 80, "Corrected",
                List.of(), List.of(), List.of(), false, "Human review", "Manager correction", null, 1));
        table.put(key(1L, 21L, hash), new Row(7, corrected, "PUBLISHED", true));
        for (var mode : PolicyRecommendationStore.OnConflict.values()) {
            var saved = store().save(1L, 21L, hash, result, mode);
            assertEquals(7, saved.id());
            assertFalse(saved.written());
        }
        assertEquals(corrected, table.get(key(1L, 21L, hash)).json);
        assertEquals("Corrected", store().getOrGenerate("user", policy,
                () -> { throw new AssertionError("Human verdict bypassed"); }, true).summary());
        verify(actions, never()).syncForAssessment(anyLong());
    }

    @Test
    void backgroundWritesKeepAnExistingResultForTheSameInputs() {
        store().getOrGenerate("user", policy, () -> result);
        String hash = store().fingerprint(profile, policy);
        var other = new ProfileImpactAssessment(ImpactUrgency.HIGH, 99, "Different", List.of(), List.of(),
                List.of(), false, "OpenAI", "model", new JudgeEvaluation(JudgeEvaluation.Verdict.YES, "Supported", "judge"), 1);
        var saved = store().save(1L, 21L, hash, other, PolicyRecommendationStore.OnConflict.KEEP_EXISTING);
        assertEquals(31, saved.id());
        assertFalse(saved.written());
        assertTrue(saved.published());
        assertEquals("Summary", store().find(1L, 21L, hash).orElseThrow().assessment().summary());
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
    void fingerprintDoesNotDependOnTheOrderSetsWereFilledIn() {
        var first = withCountries("IR", "AF", "SY");
        var second = withCountries("SY", "IR", "AF");
        assertEquals(store().fingerprint(profile, first), store().fingerprint(profile, second));
        assertEquals(List.of("AF", "IR", "SY"), List.copyOf(first.getAffectedCountries()));
    }

    private PolicyDocument withCountries(String... countries) {
        var document = new PolicyDocument("doc", "Policy", "Agency", "F-1", PolicyStatus.FINAL, SourceType.PRIMARY,
                LocalDate.of(2026, 7, 17), null, "https://example.org", "Text", "hash",
                new LinkedHashSet<>(List.of(countries)),
                new LinkedHashSet<>(List.of(RestrictionCategory.values()).reversed()), List.of());
        ReflectionTestUtils.setField(document, "id", 21L);
        return document;
    }

    @Test
    void temporaryFailureIsNotSaved() {
        var unavailable = new ProfileImpactAssessment(null, 0, "Try later",
                List.of(), List.of(), List.of(), true, "OpenAI", "model", null, 0);
        store().getOrGenerate("user", policy, () -> unavailable);
        assertTrue(table.isEmpty());
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
        assertEquals(2, table.size());
    }

    @Test
    void publishedUnverifiedCacheEntryIsReturnedAndUsedAsChatContext() throws Exception {
        store().getOrGenerate("user",policy,()->result);
        // Rows published before the shared review policy existed stay as they are.
        table.values().iterator().next().json = mapper.writeValueAsString(new ProfileImpactAssessment(ImpactUrgency.LOW,
                90,"Unchecked old result",List.of(),List.of(),List.of(),true,"OpenAI","model",null,1));
        assertTrue(store().findCurrent("user",policy).isPresent());
        boolean[] regenerated={false};
        assertEquals("Unchecked old result",store().getOrGenerate("user",policy,()->{regenerated[0]=true;return result;}).summary());
        assertFalse(regenerated[0]);
    }

    @Test
    void reviewPolicyNamesEveryReasonToHold() {
        var policyRule = new ImpactReviewPolicy(60);
        var heldForAll = new ProfileImpactAssessment(ImpactUrgency.LOW, 40, "s", List.of(), List.of(), List.of(), true,
                "OpenAI", "m", new JudgeEvaluation(JudgeEvaluation.Verdict.UNAVAILABLE, "down", "judge"), 1);
        assertEquals(List.of(ImpactReviewPolicy.HoldReason.NOT_VERIFIED, ImpactReviewPolicy.HoldReason.LOW_CONFIDENCE),
                policyRule.holdReasons(heldForAll));
        var asked = new ProfileImpactAssessment(ImpactUrgency.LOW, 90, "s", List.of(), List.of(), List.of(), true,
                "OpenAI", "m", new JudgeEvaluation(JudgeEvaluation.Verdict.YES, "ok", "judge"), 1);
        assertEquals(List.of(ImpactReviewPolicy.HoldReason.MODEL_REQUESTED), policyRule.holdReasons(asked));
        assertEquals(Set.of(), Set.copyOf(policyRule.holdReasons(result)));
        var noJudge = new ProfileImpactAssessment(ImpactUrgency.LOW, 90, "s", List.of(), List.of(), List.of(), false,
                "OpenAI", "m", null, 1);
        assertTrue(policyRule.requiresReview(noJudge));
    }
}
