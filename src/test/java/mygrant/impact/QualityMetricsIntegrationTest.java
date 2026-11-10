package mygrant.impact;

import static org.assertj.core.api.Assertions.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;
import mygrant.policies.*;
import mygrant.user.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class QualityMetricsIntegrationTest {
    static final LocalDate DAY = LocalDate.of(2032, 4, 5);
    @Autowired QualityMetricsService metrics;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PolicyDocumentRepository policies;
    User owner, manager, admin;
    PolicyDocument policy;

    @BeforeEach void setup() {
        owner = user(UserRole.APPLICANT); manager = user(UserRole.MANAGER); admin = user(UserRole.ADMIN);
        policy = policies.saveAndFlush(new PolicyDocument("metrics-"+UUID.randomUUID(), "Rule", "DHS", "F-1",
                PolicyStatus.FINAL, SourceType.PRIMARY, DAY, null, "https://example.org", "Rule text", "hash"));
    }
    User user(UserRole role) {
        var user = new User();
        user.setFullName("Metrics");
        user.setEmail(UUID.randomUUID()+"@example.org");
        user.setPasswordHash("unused");
        user.setRole(role); return users.saveAndFlush(user);
    }
    Timestamp at(String time) { return Timestamp.from(Instant.parse(time)); }
    String version(String name) {
        return org.springframework.util.DigestUtils.md5DigestAsHex(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)).repeat(2);
    }
    void evaluation(String type, String version, String verdict, Double faithfulness, Double relevance, String time) {
        jdbc.update("""
                INSERT INTO ai_output_evaluations
                    (id,output_type,output_version,verdict,faithfulness,relevance,citations_valid,model,prompt_version,evaluated_at)
                VALUES (?,?,?,?,?,?,TRUE,'fixture','fixture',?)
                """, UUID.randomUUID(), type, version(version), verdict, faithfulness, relevance, at(time));
    }
    String assessment(String verdict, boolean human) {
        return "{\"urgency\":\"LOW\",\"evaluation\":{\"verdict\":\""+verdict+"\",\"model\":\""+(human?"Human":"fixture")+"\"}}";
    }
    long review(String verdict, String state, String time) {
        long id = jdbc.queryForObject("""
                INSERT INTO policy_recommendations(user_id,policy_id,input_hash,assessment_json,publication_status)
                VALUES (?,?,?,?,?) RETURNING id
                """, Long.class, owner.getId(), policy.getId(), UUID.randomUUID().toString(), assessment(verdict,false),state);
        jdbc.update("UPDATE impact_assessment_revisions SET changed_at = ? WHERE assessment_id = ?", at(time),id);
        return id;
    }
    void revise(long id, String verdict, String state, User reviewer, String time) {
        jdbc.update("""
                UPDATE policy_recommendations SET assessment_json = ?, publication_status = ?,
                    reviewed_by_user_id = ?, reviewed_by_human = ?, version = version + 1 WHERE id = ?
                """,assessment(verdict,reviewer!=null),state,reviewer==null?null:reviewer.getId(),reviewer!=null,id);
        jdbc.update("""
                UPDATE impact_assessment_revisions SET changed_at = ? WHERE assessment_id = ? AND
                    version = (SELECT max(version) FROM impact_assessment_revisions WHERE assessment_id = ?)
                """, at(time),id,id);
    }

    @Test void latestEvaluationPerContentVersionAndUtcBoundariesHaveKnownTotals() {
        evaluation("ASSESSMENT","A","NO",.2,.4,"2032-04-05T00:00:00Z");
        evaluation("ASSESSMENT","A","YES",.9,.8,"2032-04-05T12:00:00Z");
        evaluation("ASSESSMENT","A","NO",.1,.1,"2032-04-06T00:00:00Z");
        evaluation("ANSWER","B","YES",1.,1.,"2032-04-04T23:59:59Z");
        evaluation("ANSWER","B","NO",.4,.6,"2032-04-05T01:00:00Z");
        evaluation("ANSWER","C","UNAVAILABLE",null,null,"2032-04-05T23:59:59.999999Z");
        evaluation("ANSWER","D","YES",1.,1.,"2032-04-06T00:00:00Z");
        var result=metrics.metrics(DAY,DAY);
        assertThat(result.evaluatedVersions()).isEqualTo(3);
        assertThat(result.passedVersions()).isEqualTo(1);
        assertThat(result.failedVersions()).isEqualTo(1);
        assertThat(result.unavailableVersions()).isEqualTo(1);
        assertThat(result.passRate()).isCloseTo(1./3,within(1e-9));
        assertThat(result.averageFaithfulness()).isCloseTo(.65,within(1e-9));
        assertThat(result.averageRelevance()).isCloseTo(.7,within(1e-9));
        assertThat(result.faithfulnessSampleSize()).isEqualTo(2);
        assertThat(result.relevanceSampleSize()).isEqualTo(2);
        assertThat(result.startInclusive()).isEqualTo(Instant.parse("2032-04-05T00:00:00Z"));
        assertThat(result.endExclusive()).isEqualTo(Instant.parse("2032-04-06T00:00:00Z"));
        assertThat(result.timezone()).isEqualTo("UTC");
    }

    @Test void outputTypeAndChangedContentAreSeparateVersionsButRepeatedAttemptsAreNot() {
        evaluation("ASSESSMENT","same","YES",1.,1.,"2032-04-05T01:00:00Z");
        evaluation("ANSWER","same","YES",1.,1.,"2032-04-05T01:00:00Z");
        evaluation("ANSWER","changed","NO",.3,.2,"2032-04-05T01:00:00Z");
        evaluation("ANSWER","changed","NO",.4,.3,"2032-04-05T02:00:00Z");
        assertThat(metrics.metrics(DAY,DAY).evaluatedVersions()).isEqualTo(3);
    }

    @Test void queueIncludesEarlierBacklogAndUsesHistoricalStateAtExclusiveEnd() {
        review("NO","PENDING_REVIEW","2032-04-04T12:00:00Z"); // earlier backlog
        long resolved=review("NO","PENDING_REVIEW","2032-04-05T01:00:00Z");
        revise(resolved,"YES","PUBLISHED",admin,"2032-04-05T02:00:00Z");
        long repeated=review("NO","PENDING_REVIEW","2032-04-05T03:00:00Z");
        revise(repeated,"YES","PUBLISHED",null,"2032-04-05T04:00:00Z");
        revise(repeated,"NO","PENDING_REVIEW",null,"2032-04-05T05:00:00Z");
        // A decision on the following day must not erase the earlier backlog.
        revise(repeated,"YES","PUBLISHED",admin,"2032-04-06T00:00:00Z");
        var result=metrics.metrics(DAY,DAY);
        assertThat(result.pendingReviewAtEnd()).isEqualTo(2);
        assertThat(result.reviewQueueEntries()).isEqualTo(2);
    }

    @Test void managerOverrideDenominatorExcludesAdminAndUnavailableAndCountsOneDecisionPerReview() {
        long override=review("NO","PENDING_REVIEW","2032-04-04T12:00:00Z");
        revise(override,"YES","PUBLISHED",manager,"2032-04-05T01:00:00Z");
        // Different text ensures a second human revision, still only one reviewed record.
        jdbc.update("UPDATE policy_recommendations SET assessment_json = assessment_json || ' ', version = version + 1 WHERE id = ?", override);
        jdbc.update("UPDATE impact_assessment_revisions SET changed_at = ? WHERE assessment_id = ? AND version = 3",
                at("2032-04-05T02:00:00Z"),override);
        long agreement=review("YES","PUBLISHED","2032-04-04T12:00:00Z");
        revise(agreement,"YES","PUBLISHED",manager,"2032-04-05T03:00:00Z");
        long unavailable=review("UNAVAILABLE","PENDING_REVIEW","2032-04-04T12:00:00Z");
        revise(unavailable,"YES","PUBLISHED",manager,"2032-04-05T04:00:00Z");
        long adminOverride=review("NO","PENDING_REVIEW","2032-04-04T12:00:00Z");
        revise(adminOverride,"YES","PUBLISHED",admin,"2032-04-05T05:00:00Z");
        var result=metrics.metrics(DAY,DAY);
        assertThat(result.managerReviewedOutputs()).isEqualTo(3);
        assertThat(result.comparableManagerReviews()).isEqualTo(2);
        assertThat(result.managerOverrides()).isEqualTo(1);
        assertThat(result.managerOverrideRate()).isEqualTo(.5);
    }

    @Test void emptyDataAndInvalidRangesAreExplicit() {
        var result=metrics.metrics(DAY,DAY);
        assertThat(result.evaluatedVersions()).isZero();
        assertThat(result.passRate()).isNull();
        assertThat(result.averageFaithfulness()).isNull();
        assertThat(result.averageRelevance()).isNull();
        assertThat(result.faithfulnessSampleSize()).isZero();
        assertThat(result.managerOverrideRate()).isNull();
        assertThat(result.pendingReviewAtEnd()).isZero();
        assertThatThrownBy(()->metrics.metrics(DAY.plusDays(1),DAY)).isInstanceOf(IllegalArgumentException.class);
        assertThat(metrics.metrics(null,DAY).from()).isEqualTo(DAY.minusDays(29));
    }
}
