package mygrant.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import mygrant.policies.*;
import mygrant.support.PgvectorIntegrationTestBase;
import mygrant.user.User;
import mygrant.user.UserRepository;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ImpactAssessmentPersistenceTest extends PgvectorIntegrationTestBase {
    @Autowired ImpactAssessmentRepository assessments;
    @Autowired UserRepository users;
    @Autowired PolicyDocumentRepository policies;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    @Test
    void migrationDropsLegacyColumnsAndRoundTripsAllFields() {
        Long userId = user();
        Long policyId = policy();
        var saved = assessments.saveAndFlush(completed(userId, policyId, 1));
        entityManager.clear();
        var loaded = assessments.findByUserIdAndPolicyIdAndProfileVersion(userId, policyId, 1).orElseThrow();
        assertThat(loaded).usingRecursiveComparison().ignoringFields("createdAt").isEqualTo(saved);
        assertThat(loaded.getCreatedAt()).isCloseTo(saved.getCreatedAt(),
                org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));
        assertThat(loaded.getReasons()).containsExactly("Expiry date falls within the transition period.", "引用");
        assertThat(loaded.getRecommendedActions()).containsExactly("Check the official rule");
        assertThat(assessments.findByUserIdOrderByCreatedAtDesc(userId)).hasSize(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'notifications'
                AND column_name IN ('impact_severity', 'impact_explanation', 'impact_confidence')
                """, Integer.class)).isZero();
    }

    @Test
    void unavailableResultPersistsWithoutInventingUrgencyOrConfidence() {
        var saved = assessments.saveAndFlush(new ImpactAssessment(user(), policy(), 1,
                AssessmentStatus.UNAVAILABLE, null, null, "Provider unavailable", List.of(), List.of(),
                false, AssessmentReviewStatus.NOT_REQUIRED, "openai", "test-model"));
        entityManager.clear();
        var loaded = assessments.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(AssessmentStatus.UNAVAILABLE);
        assertThat(loaded.getUrgency()).isNull();
        assertThat(loaded.getConfidence()).isNull();
        assertThat(assessments.findByStatus(AssessmentStatus.UNAVAILABLE)).extracting(ImpactAssessment::getId)
                .contains(saved.getId());
    }

    @Test
    void rejectsDuplicateUserPolicyAndProfileVersion() {
        Long userId = user();
        Long policyId = policy();
        assessments.saveAndFlush(completed(userId, policyId, 1));
        assertThatThrownBy(() -> assessments.saveAndFlush(completed(userId, policyId, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsDifferentUsersAndProfileVersionsForSamePolicy() {
        Long userId = user();
        Long policyId = policy();
        assessments.saveAndFlush(completed(userId, policyId, 1));
        assessments.saveAndFlush(completed(userId, policyId, 2));
        assessments.saveAndFlush(completed(user(), policyId, 1));
        assertThat(assessments.findByUserIdOrderByCreatedAtDesc(userId)).hasSize(2);
    }

    private ImpactAssessment completed(Long userId, Long policyId, int version) {
        return new ImpactAssessment(userId, policyId, version, AssessmentStatus.COMPLETED,
                ImpactUrgency.HIGH, 85, "Review your visa dates",
                List.of("Expiry date falls within the transition period.", "引用"),
                List.of("Check the official rule"), true, AssessmentReviewStatus.PENDING_REVIEW,
                "openai", "test-model");
    }

    private Long user() {
        return users.saveAndFlush(new User("Assessment test", UUID.randomUUID() + "@example.org", "unused")).getId();
    }

    private Long policy() {
        return policies.saveAndFlush(new PolicyDocument(UUID.randomUUID().toString(), "Test rule", "DHS", "F-1",
                PolicyStatus.FINAL, SourceType.PRIMARY, LocalDate.of(2026, 10, 1), null,
                "https://example.org/rule", "Rule text", "0".repeat(64))).getId();
    }
}
