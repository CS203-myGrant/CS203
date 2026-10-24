package mygrant.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import mygrant.policies.PolicyDocument;
import mygrant.policies.PolicyDocumentRepository;
import mygrant.policies.PolicyIndexedEvent;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;
import mygrant.testclock.TestClock;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;
import mygrant.user.UserProfile;
import mygrant.user.UserProfileRepository;

/** Verifies who is notified about a new policy and that inbox access is scoped to the owner. */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTests {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private NotificationStreamRegistry streamRegistry;

    @Mock
    private PolicyDocumentRepository policyRepository;

    private final TestClock testClock = new TestClock(true);

    @Captor
    private ArgumentCaptor<List<Notification>> saved;

    @BeforeEach
    void persistSavedRows() {
        lenient().when(notificationRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void notifiesApplicantsMatchingTheAffectedVisaTypes() {
        User student = user(7L, "student@example.com");
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1", "J-1")))
                .thenReturn(List.of(student));

        service().onPolicyIndexed(
                new PolicyIndexedEvent(3L, "Fixed Time Period of Admission", List.of("F-1", "J-1"),
                        LocalDate.parse("2026-10-01"), "Sets a fixed period of admission."));

        verify(notificationRepository).saveAll(saved.capture());
        verify(streamRegistry).publish(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.argThat(response -> response.message().contains("Fixed Time Period")));
        assertThat(saved.getValue()).singleElement().satisfies(notification -> {
            assertThat(notification.getUserId()).isEqualTo(7L);
            assertThat(notification.getPolicyId()).isEqualTo(3L);
            assertThat(notification.getMessage())
                    .contains("F-1, J-1", "Fixed Time Period of Admission", "2026-10-01");
            assertThat(notification.getReadAt()).isNull();
        });
    }

    @Test
    void pushFailureDoesNotFailNotificationCreation() {
        User student = user(7L, "student@example.com");
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1")))
                .thenReturn(List.of(student));
        doThrow(new IllegalStateException("closed stream")).when(streamRegistry)
                .publish(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any());

        service().onPolicyIndexed(new PolicyIndexedEvent(3L, "Fee update", List.of("F-1"), null, "Fees change."));

        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(1);
    }

    @Test
    void flagsActionRequiredWhenThePolicyTouchesAFilledInProfileField() {
        User student = user(7L, "student@example.com");
        student.setProgramEndDate(LocalDate.parse("2028-05-31"));
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1")))
                .thenReturn(List.of(student));

        service().onPolicyIndexed(new PolicyIndexedEvent(3L, "Fixed Time Period of Admission",
                List.of("F-1"), null, "Ends duration of status."));

        verify(notificationRepository).saveAll(saved.capture());
        verify(streamRegistry).publish(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.argThat(response -> response.actionRequired()
                        && response.affectedFields().contains("program end date")
                        && response.message().contains("Action needed: review your program end date")));
        assertThat(saved.getValue()).singleElement().satisfies(notification -> {
            assertThat(notification.isActionRequired()).isTrue();
            assertThat(notification.getAffectedFields()).containsExactly(ProfileField.PROGRAM_END_DATE);
            assertThat(notification.getMessage()).contains("Action needed: review your program end date");
        });
    }

    @Test
    void flagsOnlyTheVisaCategoryTheRuleAppliesTo() {
        User student = user(7L, "student@example.com");
        student.setProgramEndDate(LocalDate.parse("2028-05-31"));
        User worker = user(8L, "worker@example.com");
        worker.setVisaType("H-1B");
        worker.setProgramEndDate(LocalDate.parse("2028-05-31"));
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1", "H-1B")))
                .thenReturn(List.of(student, worker));

        // One rule classified into two categories: only the F-1 half concerns practical training.
        service().onPolicyIndexed(new PolicyIndexedEvent(3L, "Training and specialty occupation update",
                List.of("F-1", "H-1B"), null, "Revises optional practical training."));

        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(2);
        assertThat(saved.getValue()).filteredOn(notification -> notification.getUserId().equals(7L))
                .singleElement().satisfies(notification -> {
                    assertThat(notification.isActionRequired()).isTrue();
                    assertThat(notification.getAffectedFields())
                            .containsExactly(ProfileField.PROGRAM_END_DATE);
                });
        assertThat(saved.getValue()).filteredOn(notification -> notification.getUserId().equals(8L))
                .singleElement().satisfies(notification -> {
                    assertThat(notification.isActionRequired()).isFalse();
                    assertThat(notification.getMessage()).doesNotContain("Action needed");
                });
    }

    @Test
    void flagsUpcomingTravelForH1bWorkersOnAnEntryExitPolicy() {
        User worker = user(8L, "worker@example.com");
        worker.setVisaType("H-1B");
        worker.setUpcomingTravelDate(LocalDate.parse("2026-12-20"));
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("H-1B")))
                .thenReturn(List.of(worker));

        // Made-up wording: a rule that changes what a worker must do when crossing the border.
        service().onPolicyIndexed(new PolicyIndexedEvent(3L,
                "Biometric Entry-Exit Collection for H-1B Workers",
                List.of("H-1B"), null, "Workers provide biometrics at the port of entry on each departure."));

        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(notification -> {
            assertThat(notification.isActionRequired()).isTrue();
            assertThat(notification.getAffectedFields()).containsExactly(ProfileField.UPCOMING_TRAVEL);
            assertThat(notification.getMessage()).contains("Action needed: review your upcoming travel date");
        });
    }

    @Test
    void flagsH1bEmploymentFromTheSavedProfileOnAWorkerGracePeriodPolicy() {
        User student = user(7L, "student@example.com");
        student.setProgramEndDate(LocalDate.parse("2028-05-31"));
        User worker = user(8L, "worker@example.com");
        worker.setVisaType("H-1B");
        // The employer exists only on the versioned profile; the legacy user row has no such field.
        UserProfile workerProfile = UserProfile.fromUser(worker);
        workerProfile.setEmployer("Acme Corp");
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1", "H-1B")))
                .thenReturn(List.of(student, worker));
        when(userProfileRepository.findAllById(List.of(7L, 8L))).thenReturn(List.of(workerProfile));

        // Shaped like FR Doc 2026-18631, which the classifier also filed under F-1.
        service().onPolicyIndexed(new PolicyIndexedEvent(3L, "Eliminating the Discretionary 60-Day Grace Period",
                List.of("F-1", "H-1B"), null,
                "Removes the grace period that followed the cessation of an H-1B worker's employment."));

        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).filteredOn(notification -> notification.getUserId().equals(8L))
                .singleElement().satisfies(notification -> {
                    assertThat(notification.isActionRequired()).isTrue();
                    assertThat(notification.getAffectedFields()).containsExactly(ProfileField.EMPLOYMENT);
                    assertThat(notification.getMessage())
                            .contains("Action needed: review your employer and employment status");
                });
        assertThat(saved.getValue()).filteredOn(notification -> notification.getUserId().equals(7L))
                .singleElement().satisfies(notification -> {
                    assertThat(notification.isActionRequired()).isFalse();
                    assertThat(notification.getMessage()).doesNotContain("Action needed");
                });
    }

    @Test
    void studentPolicyNeverReachesH1bWorkers() {
        // Recipients come from the classified visa types, not the impact rules, so travel
        // wording in an F-1/J-1 policy must not pull H-1B workers into the query.
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1", "J-1")))
                .thenReturn(List.of(user(7L, "student@example.com")));

        service().onPolicyIndexed(new PolicyIndexedEvent(3L, "Biometric entry and exit for students",
                List.of("F-1", "J-1"), null, "Covers travel to the United States."));

        verify(userRepository).findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1", "J-1"));
        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(Notification::getUserId).containsExactly(7L);
    }

    @Test
    void h1bPolicyNeverReachesStudentsOrExchangeVisitors() {
        User worker = user(8L, "worker@example.com");
        worker.setVisaType("H-1B");
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("H-1B")))
                .thenReturn(List.of(worker));

        service().onPolicyIndexed(new PolicyIndexedEvent(3L, "Fee for Certain H-1B Petitions",
                List.of("H-1B"), null, "Ends duration of status and changes academic level rules."));

        verify(userRepository).findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("H-1B"));
        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(Notification::getUserId).containsExactly(8L);
    }

    @Test
    void publishesEachNotificationToItsRecipientForLivePush() {
        User student = user(7L, "student@example.com");
        User exchange = user(9L, "exchange@example.com");
        exchange.setVisaType("J-1");
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1", "J-1")))
                .thenReturn(List.of(student, exchange));

        service().onPolicyIndexed(new PolicyIndexedEvent(3L, "Fixed Time Period of Admission",
                List.of("F-1", "J-1"), null, "Sets a fixed period of admission."));

        verify(streamRegistry).publish(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.argThat(response -> response.policyId().equals(3L)));
        verify(streamRegistry).publish(org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.argThat(response -> response.message().contains("Fixed Time Period")));
    }

    @Test
    void matchesUsersWhoseVisaTypeWasStoredWithStrayCaseOrSpacing() {
        User student = user(7L, "student@example.com");
        student.setVisaType(" f-1 "); // registration stores whatever was typed
        student.setProgramEndDate(LocalDate.parse("2028-05-31"));
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1")))
                .thenReturn(List.of(student));

        service().onPolicyIndexed(new PolicyIndexedEvent(3L, "Fixed Time Period of Admission",
                List.of("F-1"), null, "Ends duration of status."));

        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement()
                .satisfies(notification -> assertThat(notification.isActionRequired()).isTrue());
    }

    @Test
    void sendsInformationalNotificationWhenNoRuleMatches() {
        User student = user(7L, "student@example.com");
        student.setProgramEndDate(LocalDate.parse("2028-05-31"));
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1")))
                .thenReturn(List.of(student));

        service().onPolicyIndexed(new PolicyIndexedEvent(3L, "Fee schedule update",
                List.of("F-1"), null, "Adjusts filing fees."));

        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(notification -> {
            assertThat(notification.isActionRequired()).isFalse();
            assertThat(notification.getMessage()).doesNotContain("Action needed");
        });
    }

    @Test
    void doesNotFlagActionWhenTheUserHasNotFilledInTheAffectedField() {
        User student = user(7L, "student@example.com"); // no program end date, no academic level
        student.setAcademicLevel(null);
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1")))
                .thenReturn(List.of(student));

        service().onPolicyIndexed(new PolicyIndexedEvent(3L, "Fixed Time Period of Admission",
                List.of("F-1"), null, "Ends duration of status."));

        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement()
                .satisfies(notification -> assertThat(notification.isActionRequired()).isFalse());
    }

    @Test
    void holdsBackAlertsFromReadersWhoseTestClockIsBeforePublication() {
        User early = user(7L, "early@example.com");
        User current = user(8L, "current@example.com");
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1")))
                .thenReturn(List.of(early, current));
        testClock.setUserDate(7L, LocalDate.parse("2026-08-31"));

        service().onPolicyIndexed(new PolicyIndexedEvent(3L, "Fee update", List.of("F-1"), null,
                "Fees change.", LocalDate.parse("2026-09-01")));

        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement()
                .satisfies(notification -> assertThat(notification.getUserId()).isEqualTo(8L));
    }

    @Test
    void testClockSyncAlertsOnlyPoliciesForTheUsersCurrentCategory() {
        User worker = user(7L, "worker@example.com");
        worker.setVisaType("H-1B");
        testClock.setUserDate(7L, LocalDate.parse("2026-09-30"));
        LocalDate today = LocalDate.parse("2026-09-30");
        when(notificationRepository.deleteForPoliciesPublishedAfter(7L, today)).thenReturn(2);
        when(notificationRepository.findPolicyIdsByUserId(7L)).thenReturn(java.util.Set.of(1L));
        when(policyRepository.findBySourceTypeAndPublicationDateLessThanEqualOrderByPublicationDateAscIdAsc(
                SourceType.PRIMARY, today)).thenReturn(List.of(
                        policy(1L, "Already alerted", "H-1B"),
                        policy(2L, "Student rule", "F-1,J-1"),
                        policy(3L, "Worker fee rule", "F-1,H-1B")));

        var sync = service().syncToTestClock(worker);

        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(notification -> {
            assertThat(notification.getPolicyId()).isEqualTo(3L);
            assertThat(notification.getMessage()).contains("Worker fee rule");
        });
        verify(streamRegistry).publish(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any());
        assertThat(sync).isEqualTo(new NotificationService.InboxSync(1, 2));
    }

    @Test
    void markReadOnlyFindsTheCallersOwnNotification() {
        when(userRepository.findByEmail("student@example.com"))
                .thenReturn(Optional.of(user(7L, "student@example.com")));
        when(notificationRepository.findByIdAndUserId(99L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service()
                .markRead("student@example.com", 99L))
                .isInstanceOf(NotificationNotFoundException.class);
    }

    @Test
    void getOnlyReturnsTheCallersOwnNotification() {
        Notification own = new Notification(7L, 1, 3L, "Own alert", List.of());
        when(userRepository.findByEmail("student@example.com"))
                .thenReturn(Optional.of(user(7L, "student@example.com")));
        when(notificationRepository.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(own));
        // Notification 2 belongs to someone else, so the owner-scoped lookup finds nothing.
        when(notificationRepository.findByIdAndUserId(2L, 7L)).thenReturn(Optional.empty());
        when(notificationRepository.findByIdAndUserId(99L, 7L)).thenReturn(Optional.empty());

        assertThat(service().get("student@example.com", 1L).message()).isEqualTo("Own alert");
        Throwable otherUsers = org.assertj.core.api.Assertions.catchThrowable(
                () -> service().get("student@example.com", 2L));
        Throwable missing = org.assertj.core.api.Assertions.catchThrowable(
                () -> service().get("student@example.com", 99L));
        assertThat(otherUsers).isInstanceOf(NotificationNotFoundException.class);
        assertThat(missing).isInstanceOf(NotificationNotFoundException.class);
        assertThat(otherUsers.getMessage()).isEqualTo(missing.getMessage().replace("99", "2"));
    }

    @Test
    void markReadSetsReadTimestamp() {
        Notification notification = new Notification(7L, 1, 3L, "msg", List.of());
        when(userRepository.findByEmail("student@example.com"))
                .thenReturn(Optional.of(user(7L, "student@example.com")));
        when(notificationRepository.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(notification));

        var response = service()
                .markRead("student@example.com", 1L);

        assertThat(response.read()).isTrue();
        assertThat(notification.getReadAt()).isNotNull();
    }

        @Test
        void savesTheProfileVersionUsedToGenerateTheNotification() {
        User student = user(7L, "student@example.com");

        UserProfile profile = UserProfile.fromUser(student);
        profile.setVersion(4);

        when(userRepository.findByRoleAndVisaTypeIn(
                UserRole.APPLICANT,
                List.of("F-1")
        )).thenReturn(List.of(student));

        when(userProfileRepository.findAllById(List.of(7L)))
                .thenReturn(List.of(profile));

        service().onPolicyIndexed(new PolicyIndexedEvent(
                3L,
                "Fee schedule update",
                List.of("F-1"),
                null,
                "Adjusts filing fees."
        ));

        verify(notificationRepository).saveAll(saved.capture());

        assertThat(saved.getValue())
                .singleElement()
                .satisfies(notification ->
                        assertThat(notification.getProfileVersion()).isEqualTo(4));
        }

    private NotificationService service() {
        return new NotificationService(
                notificationRepository,
                userRepository,
                userProfileRepository,
                new PolicyImpactRules(),
                streamRegistry,
                policyRepository,
                testClock
        );
    }

    private PolicyDocument policy(Long id, String title, String visaTypes) {
        PolicyDocument policy = new PolicyDocument("FR-" + id, title, "DHS", visaTypes, PolicyStatus.FINAL,
                SourceType.PRIMARY, LocalDate.parse("2026-09-01"), null, "https://example.gov", "Text.", "hash");
        ReflectionTestUtils.setField(policy, "id", id);
        return policy;
    }

    private User user(Long id, String email) {
        User user = new User("Student", email, "hash", "F-1", "Master's", null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
