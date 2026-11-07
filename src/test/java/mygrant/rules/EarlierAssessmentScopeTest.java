package mygrant.rules;

import static mygrant.rules.RuleFixtures.FINAL_RULE;
import static mygrant.rules.RuleFixtures.OLD_PROPOSAL;
import static mygrant.rules.RuleFixtures.PROPOSAL;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import mygrant.ingestion.PolicyIngestionService;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

/** The default reassessment scope: users assessed on an earlier document of the same rule. Rolls back. */
@SpringBootTest
@Transactional
@RecordApplicationEvents
class EarlierAssessmentScopeTest {

    @Autowired PolicyIngestionService ingestion;
    @Autowired EarlierAssessmentScope scope;
    @Autowired UserRepository users;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired ApplicationEvents events;
    @MockBean VectorStore vectors;

    @BeforeEach
    void isolateFixtures() {
        RuleFixtures.isolate(jdbc, List.of(PROPOSAL, FINAL_RULE, OLD_PROPOSAL));
    }

    @Test
    void takesActiveUsersAssessedOnAnEarlierDocumentOfTheRule() {
        long proposal = ingestion.ingest(RuleFixtures.proposal()).policyId();
        long oldProposal = ingestion.ingest(RuleFixtures.oldProposal()).policyId();
        User assessedOnProposal = user(true);
        User assessedOnOtherRule = user(true);
        User deactivated = user(true);
        user(true); // never assessed anything
        assessed(assessedOnProposal, proposal);
        assessed(assessedOnOtherRule, oldProposal);
        assessed(deactivated, proposal);
        deactivated.setActive(false);
        users.saveAndFlush(deactivated);

        ingestion.ingest(RuleFixtures.finalRule());
        PolicyChangedEvent change = events.stream(PolicyChangedEvent.class).findFirst().orElseThrow();

        assertThat(scope.usersToReassess(change)).containsExactly(assessedOnProposal.getEmail());
    }

    private User user(boolean active) {
        User user = new User("Scope test", "scope-" + UUID.randomUUID() + "@example.com", "unused", "F-1", null, null);
        user.setRole(UserRole.APPLICANT);
        user.setActive(active);
        return users.saveAndFlush(user);
    }

    private void assessed(User user, long policyId) {
        jdbc.update("""
                INSERT INTO policy_recommendations (user_id, policy_id, input_hash, assessment_json)
                VALUES (:user, :policy, :hash, '{}')
                """, Map.of("user", user.getId(), "policy", policyId, "hash", UUID.randomUUID().toString()));
    }
}
