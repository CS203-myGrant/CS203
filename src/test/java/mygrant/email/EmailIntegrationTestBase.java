package mygrant.email;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import mygrant.notifications.Notification;
import mygrant.notifications.NotificationRepository;
import mygrant.notifications.NotificationService;
import mygrant.notifications.NotificationStreamRegistry;
import mygrant.policies.PolicyDocument;
import mygrant.policies.PolicyDocumentRepository;
import mygrant.policies.PolicyIndexedEvent;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

/**
 * Runs the email code against the local PostgreSQL with a fake provider. These tests are not
 * @Transactional, because claims have to commit for a concurrent run to see them, so every
 * row they create is removed afterwards. Each test gives its users a visa type of its own,
 * and drives the email services for its own rows only, never the whole-table job loops.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EmailIntegrationTestBase.FakeSenderConfig.class)
abstract class EmailIntegrationTestBase {

    @TestConfiguration
    static class FakeSenderConfig {
        @Bean
        @Primary
        FakeEmailSender fakeEmailSender() {
            return new FakeEmailSender();
        }
    }

    @Autowired protected FakeEmailSender sender;
    @Autowired protected UserRepository users;
    @Autowired protected PolicyDocumentRepository policies;
    @Autowired protected NotificationRepository notifications;
    @Autowired protected NotificationService notificationService;
    @Autowired protected EmailDeliveryRepository deliveries;
    @Autowired protected EmailPreferenceRepository preferences;
    @Autowired protected EmailOutbox outbox;
    @Autowired protected EmailDispatcher dispatcher;
    @Autowired protected JdbcTemplate jdbc;
    @MockBean protected VectorStore vectors;
    @SpyBean protected NotificationStreamRegistry streams;

    protected final String visaType = "T-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    private final List<Long> createdUsers = new ArrayList<>();
    private final List<Long> createdPolicies = new ArrayList<>();

    @BeforeEach
    void resetSender() {
        sender.reset();
    }

    @AfterEach
    void removeCreatedRows() {
        // Notifications go with their policy; deliveries and preferences with their user. A
        // purge leaves an audit row that blocks deleting the user, so it goes first.
        createdPolicies.forEach(id -> jdbc.update("DELETE FROM policy_documents WHERE id = ?", id));
        createdUsers.forEach(id -> {
            jdbc.update("DELETE FROM account_deletion_audit WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        });
    }

    /** An applicant signed in through WorkOS, so their address counts as verified. */
    protected User applicant() {
        String suffix = UUID.randomUUID().toString();
        User user = new User("Email test", "email-test-" + suffix + "@example.com", null, visaType, null, null);
        user.setRole(UserRole.APPLICANT);
        user.setWorkosUserId("user_email_test_" + suffix);
        user = users.saveAndFlush(user);
        createdUsers.add(user.getId());
        return user;
    }

    /** Indexes a policy for this test's visa type and returns the alert each applicant got. */
    protected List<Notification> publishPolicy(String title, User... recipients) {
        PolicyDocument policy = policies.saveAndFlush(new PolicyDocument("email-test-" + UUID.randomUUID(), title,
                "Test agency", visaType, PolicyStatus.FINAL, SourceType.PRIMARY, LocalDate.parse("2026-10-01"),
                null, "https://example.com/rule", "Rule text.", "0".repeat(64)));
        createdPolicies.add(policy.getId());
        notificationService.onPolicyIndexed(new PolicyIndexedEvent(policy.getId(), title, List.of(visaType),
                null, "Rule text."));
        List<Notification> created = new ArrayList<>();
        for (User recipient : recipients) {
            created.add(notifications.findByUserIdOrderByCreatedAtDesc(recipient.getId(), PageRequest.of(0, 1))
                    .getContent().get(0));
        }
        return created;
    }

    /** Marks an alert as rated HIGH, which makes it critical; no rule flags these test policies. */
    protected void rateHigh(Notification notification) {
        jdbc.update("UPDATE notifications SET impact_severity = 'HIGH' WHERE id = ?", notification.getId());
    }

    protected UUID emailOf(Notification notification) {
        return notifications.findById(notification.getId()).orElseThrow().getEmailDeliveryId();
    }

    protected EmailDelivery delivery(UUID id) {
        return deliveries.findById(id).orElseThrow();
    }
}
