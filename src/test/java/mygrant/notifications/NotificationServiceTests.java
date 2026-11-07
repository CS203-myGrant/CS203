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
import mygrant.rules.PolicyChangedEvent;
import mygrant.rules.PolicyRuleStore;
import mygrant.rules.RuleChange;
import mygrant.rules.RuleSource;
import mygrant.rules.RuleState;
import mygrant.testclock.TestClock;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;
import mygrant.user.UserProfile;
import mygrant.user.UserProfileRepository;
import mygrant.user.ImmigrationDocument;
import mygrant.user.ImmigrationDocumentRepository;
import mygrant.user.ImmigrationDocumentType;
import mygrant.user.ProfileFieldConsentRepository;
import mygrant.user.ProfileFieldConsent;
import mygrant.user.ProfileFieldDefinition;
import mygrant.policies.PolicyRecommendationStore;

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

    @Mock
    private PolicyRuleStore ruleStore;

    @Mock private ImmigrationDocumentRepository immigrationDocuments;
    @Mock private ProfileFieldConsentRepository consents;
    @Mock private PolicyRecommendationStore recommendations;

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
    void aPolicyThatMovedItsRuleGetsNoGenericAlert() {
        service().onPolicyIndexed(new PolicyIndexedEvent(3L, "Fixed Time Period of Admission", List.of("F-1"),
                LocalDate.parse("2026-09-15"), "Final rule.", LocalDate.parse("2026-07-17"), true));

        verify(notificationRepository, org.mockito.Mockito.never()).saveAll(anyList());
        verify(userRepository, org.mockito.Mockito.never()).findByRoleAndVisaTypeIn(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aRuleChangeAlertSaysWhatMovedAndBelongsToTheDocumentThatMovedIt() {
        User student = user(7L, "student@example.com");
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1", "J-1")))
                .thenReturn(List.of(student));

        service().onPolicyChanged(change(5L, new RuleState(PolicyStatus.FINAL, LocalDate.parse("2026-09-15"), false),
                new RuleState(PolicyStatus.FINAL, LocalDate.parse("2026-11-01"), false), "Homeland Security Department"));

        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(notification -> {
            assertThat(notification.getPolicyId()).isEqualTo(5L);
            assertThat(notification.getMessage()).isEqualTo("Rule update for F-1, J-1: effective date moved from "
                    + "15 Sep 2026 to 1 Nov 2026: Fixed Time Period of Admission");
        });
        verify(streamRegistry).publish(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aRepeatedChangeEventSkipsReadersAlreadyAlerted() {
        User alerted = user(7L, "student@example.com");
        User other = user(8L, "other@example.com");
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1", "J-1")))
                .thenReturn(List.of(alerted, other));
        when(notificationRepository.findUserIdsByPolicyId(5L)).thenReturn(java.util.Set.of(7L));

        service().onPolicyChanged(change(5L, new RuleState(PolicyStatus.PROPOSED, null, false),
                new RuleState(PolicyStatus.FINAL, LocalDate.parse("2026-09-15"), false), "Homeland Security Department"));

        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement()
                .satisfies(notification -> assertThat(notification.getUserId()).isEqualTo(8L));
    }

    @Test
    void theTestClockSyncUsesTheChangeWordingForADocumentThatMovedItsRule() {
        User student = user(7L, "student@example.com");
        LocalDate today = LocalDate.parse("2026-09-30");
        testClock.setUserDate(7L, today);
        when(userProfileRepository.findById(7L)).thenReturn(Optional.of(
                profile(student, "F-1", null, null, null, null)));
        when(policyRepository.findBySourceTypeAndPublicationDateLessThanEqualOrderByPublicationDateAscIdAsc(
                SourceType.PRIMARY, today)).thenReturn(List.of(policy(5L, "Fixed Time Period of Admission", "F-1,J-1")));
        var row = new PolicyRuleStore.HistoryRow(1L, 9L, new RuleState(PolicyStatus.FINAL, LocalDate.parse("2026-09-15"), false),
                new RuleState(PolicyStatus.PROPOSED, null, false), true, true, false,
                new RuleSource(5L, "FR-5", SourceType.PRIMARY, "https://example.gov", "Fixed Time Period of Admission",
                        "DHS", LocalDate.parse("2026-09-01")), mygrant.rules.RecordedBy.INGESTION, java.time.Instant.now());
        when(ruleStore.alertedChangeFrom(5L)).thenReturn(Optional.of(
                new PolicyRuleStore.AlertedChange("Fixed Time Period of Admission", row)));

        service().syncToTestClock(student);

        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(notification -> assertThat(notification.getMessage())
                .isEqualTo("Rule update for F-1, J-1: moved from proposed to final, effective 15 Sep 2026: "
                        + "Fixed Time Period of Admission"));
    }

    private static PolicyChangedEvent change(long sourceId, RuleState previous, RuleState current, String agency) {
        return new PolicyChangedEvent(9L, 1L, "Fixed Time Period of Admission", previous, current,
                RuleChange.between(previous, current),
                new RuleSource(sourceId, "FR-" + sourceId, SourceType.PRIMARY, "https://example.gov",
                        "Fixed Time Period of Admission", agency, LocalDate.parse("2026-09-01")),
                List.of("F-1", "J-1"), "Fixed Time Period of Admission", "Sets a fixed period of admission.");
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
    void matchesExplicitlyConsentedPalestinianAuthorityTravelDocumentHolderCase() {
        User traveler = user(7L, "traveler@example.com");
        when(userRepository.findByRole(UserRole.APPLICANT)).thenReturn(List.of(traveler));
        when(immigrationDocuments.findByUserIdAndPolicyMatchingConsentTrue(7L)).thenReturn(List.of(
                new ImmigrationDocument(7L, ImmigrationDocumentType.TRAVEL_DOCUMENT, "travel.pdf",
                        "application/pdf", 10, new byte[] {1}, "PA", true)));
        NotificationService service = new NotificationService(notificationRepository, userRepository,
                userProfileRepository, new PolicyImpactRules(), streamRegistry, policyRepository,
                testClock, ruleStore, consents, immigrationDocuments, recommendations);

        service.onPolicyIndexed(new PolicyIndexedEvent(3L, "Entry proclamation", List.of("ENTRY_RESTRICTION"),
                null, "Restriction applies to PA-issued travel documents.", LocalDate.now(), java.util.Set.of(),
                true, false, true));

        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(Notification::getUserId).containsExactly(7L);
        assertThat(saved.getValue().getFirst().getMessage()).contains("travelers using a PA-issued travel document");
    }

    @Test
    void entryRestrictionDoesNotNotifyVisaMatchesOutsideAffectedCountries() {
        User affectedCitizen = user(7L, "affected@example.com");
        User incidentalVisaMention = user(8L, "other@example.com");
        UserProfile affectedProfile = profile(affectedCitizen, "F-1", null, null, null, null);
        affectedProfile.setCountryOfCitizenship("Afghanistan");
        UserProfile otherProfile = profile(incidentalVisaMention, "F-1", null, null, null, null);
        otherProfile.setCountryOfCitizenship("Malaysia");
        when(userRepository.findByRole(UserRole.APPLICANT))
                .thenReturn(List.of(affectedCitizen, incidentalVisaMention));
        when(consents.findByUserId(7L)).thenReturn(List.of(new ProfileFieldConsent(7L,
                ProfileFieldDefinition.COUNTRY_OF_CITIZENSHIP, java.time.Instant.now())));
        when(consents.findByUserId(8L)).thenReturn(List.of(new ProfileFieldConsent(8L,
                ProfileFieldDefinition.COUNTRY_OF_CITIZENSHIP, java.time.Instant.now())));
        when(userProfileRepository.findById(7L)).thenReturn(Optional.of(affectedProfile));
        when(userProfileRepository.findById(8L)).thenReturn(Optional.of(otherProfile));
        lenient().when(userProfileRepository.findAllById(List.of(7L))).thenReturn(List.of(affectedProfile));

        new NotificationService(notificationRepository, userRepository, userProfileRepository,
                new PolicyImpactRules(), streamRegistry, policyRepository, testClock,
                ruleStore, consents, immigrationDocuments, recommendations)
                .onPolicyIndexed(new PolicyIndexedEvent(3L, "Entry proclamation mentioning F-1",
                List.of("F-1"), null, "Entry restrictions for nationals of Afghanistan.",
                null, java.util.Set.of("AF"), true, false, false));

        verify(userRepository, org.mockito.Mockito.never())
                .findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1"));
        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(Notification::getUserId).containsExactly(7L);
    }

    @Test
    void entryRestrictionDoesNotMatchCitizenshipWithoutConsent() {
        User applicant = user(7L, "applicant@example.com");
        UserProfile profile = profile(applicant, "F-1", null, null, null, null);
        profile.setCountryOfCitizenship("Afghanistan");
        when(userRepository.findByRole(UserRole.APPLICANT)).thenReturn(List.of(applicant));
        new NotificationService(notificationRepository, userRepository, userProfileRepository,
                new PolicyImpactRules(), streamRegistry, policyRepository, testClock,
                ruleStore, consents, immigrationDocuments, recommendations)
                .onPolicyIndexed(new PolicyIndexedEvent(3L, "Entry proclamation",
                List.of("ENTRY_RESTRICTION"), null, "Entry restrictions for nationals of Afghanistan.",
                null, java.util.Set.of("AF"), true, false, false));

        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).isEmpty();
    }

    @Test
    void flagsActionRequiredWhenThePolicyTouchesAFilledInProfileField() {
        User student = user(7L, "student@example.com");
        UserProfile studentProfile = profile(student, "F-1", null,
                LocalDate.parse("2028-05-31"), null, null);
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1")))
                .thenReturn(List.of(student));
        when(userProfileRepository.findAllById(List.of(7L))).thenReturn(List.of(studentProfile));

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
        User worker = user(8L, "worker@example.com");
        UserProfile studentProfile = profile(student, "F-1", null,
                LocalDate.parse("2028-05-31"), null, null);
        UserProfile workerProfile = profile(worker, "H-1B", null,
                LocalDate.parse("2028-05-31"), null, null);
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1", "H-1B")))
                .thenReturn(List.of(student, worker));
        when(userProfileRepository.findAllById(List.of(7L, 8L)))
                .thenReturn(List.of(studentProfile, workerProfile));

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
        UserProfile workerProfile = profile(worker, "H-1B", null, null, null,
                LocalDate.parse("2026-12-20"));
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("H-1B")))
                .thenReturn(List.of(worker));
        when(userProfileRepository.findAllById(List.of(8L))).thenReturn(List.of(workerProfile));

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
        UserProfile studentProfile = UserProfile.registrationProfile(
                student.getId(),
                "F-1",
                null,
                LocalDate.parse("2028-05-31"),
                null,
                null
        );

        User worker = user(8L, "worker@example.com");
        UserProfile workerProfile = UserProfile.registrationProfile(
                worker.getId(),
                "H-1B",
                null,
                null,
                null,
                null
        );
        workerProfile.setEmployer("Acme Corp");
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1", "H-1B")))
                .thenReturn(List.of(student, worker));
        when(userProfileRepository.findAllById(List.of(7L, 8L)))
                .thenReturn(List.of(studentProfile, workerProfile));

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
        UserProfile studentProfile = profile(student, " f-1 ", null,
                LocalDate.parse("2028-05-31"), null, null);
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1")))
                .thenReturn(List.of(student));
        when(userProfileRepository.findAllById(List.of(7L))).thenReturn(List.of(studentProfile));

        service().onPolicyIndexed(new PolicyIndexedEvent(3L, "Fixed Time Period of Admission",
                List.of("F-1"), null, "Ends duration of status."));

        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement()
                .satisfies(notification -> assertThat(notification.isActionRequired()).isTrue());
    }

    @Test
    void sendsInformationalNotificationWhenNoRuleMatches() {
        User student = user(7L, "student@example.com");
        UserProfile studentProfile = profile(student, "F-1", null,
                LocalDate.parse("2028-05-31"), null, null);
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("F-1")))
                .thenReturn(List.of(student));
        when(userProfileRepository.findAllById(List.of(7L))).thenReturn(List.of(studentProfile));

        service().onPolicyIndexed(new PolicyIndexedEvent(3L, "Fee schedule update",
                List.of("F-1"), null, "Adjusts filing fees."));

        verify(notificationRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(notification -> {
            assertThat(notification.isActionRequired()).isFalse();
            assertThat(notification.getMessage()).doesNotContain("Action needed");
        });
    }

    @Test
    void aDelayNoticesGenericAlertLeavesOutTheFederalRegistersEffectiveOn() {
        // Sent only when its new date could not be read; effective_on is not that date.
        User worker = user(7L, "worker@example.com");
        when(userRepository.findByRoleAndVisaTypeIn(UserRole.APPLICANT, List.of("H-1B"))).thenReturn(List.of(worker));
        String title = "Modification of Registration Requirement; Delay of Effective Date";

        service().onPolicyIndexed(new PolicyIndexedEvent(3L, title, List.of("H-1B"), LocalDate.parse("2021-01-08"),
                "ACTION: Final rule; delay of effective date.\n\nSUMMARY: The rule is delayed."));
        service().onPolicyIndexed(new PolicyIndexedEvent(4L, "Fee schedule update", List.of("H-1B"),
                LocalDate.parse("2021-03-09"), "ACTION: Final rule.\n\nSUMMARY: Adjusts filing fees."));

        verify(notificationRepository, org.mockito.Mockito.times(2)).saveAll(saved.capture());
        assertThat(saved.getAllValues()).extracting(batch -> batch.getFirst().getMessage()).containsExactly(
                "New policy that may affect H-1B: " + title,
                "New policy that may affect H-1B: Fee schedule update (effective 2021-03-09)");
    }

    @Test
    void doesNotFlagActionWhenTheUserHasNotFilledInTheAffectedField() {
        User student = user(7L, "student@example.com"); // no program end date, no academic level
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
        when(userProfileRepository.findById(7L)).thenReturn(Optional.of(
                profile(worker, "H-1B", null, null, null, null)));
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

        UserProfile profile = UserProfile.registrationProfile(
                student.getId(),
                "F-1",
                null,
                null,
                null,
                null
        );
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
                testClock,
                ruleStore
        );
    }

    private PolicyDocument policy(Long id, String title, String visaTypes) {
        PolicyDocument policy = new PolicyDocument("FR-" + id, title, "DHS", visaTypes, PolicyStatus.FINAL,
                SourceType.PRIMARY, LocalDate.parse("2026-09-01"), null, "https://example.gov", "Text.", "hash");
        ReflectionTestUtils.setField(policy, "id", id);
        return policy;
    }

    private User user(Long id, String email) {
        User user = new User("Student", email, "hash");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private UserProfile profile(User user, String visaType, String academicLevel,
            LocalDate programEndDate, String currentLocation, LocalDate upcomingTravelDate) {
        return UserProfile.registrationProfile(
                user.getId(), visaType, academicLevel, programEndDate, currentLocation, upcomingTravelDate);
    }
}
