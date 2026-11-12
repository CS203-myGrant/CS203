package mygrant.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import mygrant.ingestion.PolicyIngestionService;
import mygrant.ingestion.dto.IngestPolicyRequest;
import mygrant.manager.ManagerMembershipRepository;
import mygrant.user.ProfileFieldConsentRepository;
import mygrant.user.UserProfileRepository;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

@SpringBootTest(properties = {
        "mygrant.demo.enabled=true",
        "mygrant.demo.evaluate=false",
        "openai.api-key=integration-test-only-key",
        "mygrant.rules.backfill-on-startup=false",
        "mygrant.email.jobs-enabled=false"
})
@ActiveProfiles("dev")
class DemoSeedIntegrationTest {
    private static final String TEST_PASSWORD = java.util.UUID.randomUUID().toString();

    @DynamicPropertySource
    static void demoPasswords(DynamicPropertyRegistry registry) {
        registry.add("mygrant.demo.applicant-password", () -> TEST_PASSWORD);
        registry.add("mygrant.demo.manager-password", () -> TEST_PASSWORD);
        registry.add("mygrant.demo.admin-password", () -> TEST_PASSWORD);
    }

    @Autowired DemoDataRunner seed;
    @Autowired UserRepository users;
    @Autowired UserProfileRepository profiles;
    @Autowired ProfileFieldConsentRepository consents;
    @Autowired ManagerMembershipRepository memberships;
    @MockBean PolicyIngestionService ingestion;

    @Test
    void startupCreatesAccountsProfilesAndLinksIdempotently() throws Exception {
        var student = users.findByEmail("demo.student.b@example.test").orElseThrow();
        var manager = users.findByEmail("demo.manager@example.test").orElseThrow();
        var employee = users.findByEmail("demo.employee@example.test").orElseThrow();
        var secondEmployee = users.findByEmail("demo.employee.two@example.test").orElseThrow();
        var admin = users.findByEmail("demo.admin@example.test").orElseThrow();

        assertThat(student.getRole()).isEqualTo(UserRole.APPLICANT);
        assertThat(student.getPasswordHash()).startsWith("$2").doesNotContain(TEST_PASSWORD);
        assertThat(profiles.findById(student.getId()).orElseThrow().getCountryOfCitizenship()).isEqualTo("Iran");
        assertThat(consents.findByUserId(student.getId())).hasSize(6);
        assertThat(manager.getRole()).isEqualTo(UserRole.MANAGER);
        assertThat(admin.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(memberships.findAllByManagerIdOrderByCreatedAtAscUserIdAsc(manager.getId()))
                .extracting(link -> link.getUserId()).containsExactlyInAnyOrder(employee.getId(), secondEmployee.getId());
        verify(ingestion, times(4)).ingest(org.mockito.ArgumentMatchers.any(IngestPolicyRequest.class));

        seed.run(null);

        assertThat(users.findByEmail("demo.student.b@example.test").orElseThrow().getId()).isEqualTo(student.getId());
        assertThat(consents.findByUserId(student.getId())).hasSize(6);
        assertThat(memberships.findAllByManagerIdOrderByCreatedAtAscUserIdAsc(manager.getId())).hasSize(2);
    }
}
