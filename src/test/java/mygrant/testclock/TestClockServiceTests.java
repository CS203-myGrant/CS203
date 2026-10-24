package mygrant.testclock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import mygrant.ingestion.federalregister.FederalRegisterClient;
import mygrant.ingestion.federalregister.FederalRegisterImportRequest;
import mygrant.ingestion.federalregister.FederalRegisterIngestionService;
import mygrant.notifications.NotificationService;
import mygrant.notifications.NotificationService.InboxSync;
import mygrant.policies.PolicyDocument;
import mygrant.policies.PolicyDocumentRepository;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

/** Verifies the admin's clock imports the Federal Register window before re-matching inboxes. */
@ExtendWith(MockitoExtension.class)
class TestClockServiceTests {

    private static final LocalDate DATE = LocalDate.parse("2022-01-21");

    @Mock
    private UserRepository userRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private FederalRegisterClient federalRegisterClient;

    @Mock
    private FederalRegisterIngestionService ingestionService;

    @Mock
    private PolicyDocumentRepository policyRepository;

    private final TestClock testClock = new TestClock(true);

    @Test
    void importsTheWindowBeforeTheDateThenSyncsEveryApplicant() {
        User admin = user(1L, "admin@example.com", UserRole.ADMIN);
        User student = user(2L, "student@example.com", UserRole.APPLICANT);
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(userRepository.findAllByActiveTrueOrderByIdAsc()).thenReturn(List.of(admin, student));
        when(federalRegisterClient.searchDocumentNumbers(DATE.minusDays(30), DATE, 20))
                .thenReturn(List.of("2022-01188", "2022-00804", "2021-27714", "2022-00942"));
        when(policyRepository.findByExternalId(any())).thenReturn(Optional.empty());
        when(policyRepository.findByExternalId("2021-27714")).thenReturn(Optional.of(org.mockito.Mockito.mock(PolicyDocument.class)));
        when(ingestionService.ingest(any(FederalRegisterImportRequest.class))).thenAnswer(invocation -> {
            FederalRegisterImportRequest request = invocation.getArgument(0);
            return switch (request.documentNumber()) {
                case "2022-00804" -> throw new IllegalArgumentException("No supported visa category was detected");
                case "2022-00942" -> throw new IllegalStateException("Ollama is down");
                default -> null;
            };
        });
        when(notificationService.syncToTestClock(student)).thenReturn(new InboxSync(1, 3));

        var response = service().setGlobalDate("admin@example.com", DATE, true);

        assertThat(response.today()).isEqualTo(DATE);
        assertThat(response.policiesFound()).isEqualTo(4);
        assertThat(response.policiesImported()).isEqualTo(1);
        assertThat(response.policiesSkipped()).isEqualTo(2);
        assertThat(response.importFailures()).containsExactly("2022-00942: IllegalStateException");
        assertThat(response.notificationsAdded()).isEqualTo(1);
        assertThat(response.notificationsRemoved()).isEqualTo(3);
        // Bulk imports skip the billed AI assessment, and inboxes sync only after importing.
        InOrder order = inOrder(ingestionService, notificationService);
        order.verify(ingestionService).ingest(argThat((FederalRegisterImportRequest request) ->
                request.documentNumber().equals("2022-01188") && !request.assessImpact()));
        order.verify(notificationService).syncToTestClock(student);
        verify(notificationService, never()).syncToTestClock(admin);
    }

    @Test
    void resettingTheClockImportsNothing() {
        User admin = user(1L, "admin@example.com", UserRole.ADMIN);
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(userRepository.findAllByActiveTrueOrderByIdAsc()).thenReturn(List.of(admin));

        var response = service().setGlobalDate("admin@example.com", null, false);

        assertThat(response.policiesFound()).isZero();
        verify(federalRegisterClient, never()).searchDocumentNumbers(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    private TestClockService service() {
        return new TestClockService(testClock, userRepository, notificationService,
                federalRegisterClient, ingestionService, policyRepository);
    }

    private User user(Long id, String email, UserRole role) {
        User user = new User("Name", email, "hash", "F-1", null, null);
        user.setRole(role);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
