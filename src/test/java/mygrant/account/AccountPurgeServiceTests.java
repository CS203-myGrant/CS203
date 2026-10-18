package mygrant.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import mygrant.notifications.NotificationRepository;
import mygrant.notifications.NotificationStreamRegistry;
import mygrant.user.ImmigrationDocumentRepository;
import mygrant.user.User;
import mygrant.user.UserProfileRepository;
import mygrant.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class AccountPurgeServiceTests {

    private static final Long USER_ID = 7L;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private ImmigrationDocumentRepository immigrationDocumentRepository;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private AccountDeletionRequestRepository deletionRequestRepository;

    @Mock
    private AccountDeletionAuditRepository auditRepository;

    @Mock
    private NotificationStreamRegistry notificationStreamRegistry;

    private User user;
    private AccountPurgeService purgeService;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(USER_ID);
        user.setFullName("Student");
        user.setEmail("student@example.com");
        user.setPasswordHash("password-hash");
        user.setWorkosUserId("user_workos_123");
        user.setVisaType("F-1");

        purgeService = new AccountPurgeService(
                userRepository,
                userProfileRepository,
                immigrationDocumentRepository,
                notificationRepository,
                deletionRequestRepository,
                auditRepository,
                notificationStreamRegistry);
    }

    @Test
    void purgeRemovesUserDataAnonymizesAccountAndClosesStreams() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(auditRepository.existsByUserId(USER_ID)).thenReturn(false);

        purgeService.purge(USER_ID);

        verify(notificationRepository).deleteAllByUserId(USER_ID);
        verify(userProfileRepository).deleteById(USER_ID);
        verify(immigrationDocumentRepository).deleteAllByUserId(USER_ID);
        verify(deletionRequestRepository).deleteAllByUserId(USER_ID);
        verify(notificationStreamRegistry).closeAll(USER_ID);
        verify(userRepository).save(user);
        org.mockito.ArgumentCaptor<AccountDeletionAudit> auditCaptor =
        org.mockito.ArgumentCaptor.forClass(AccountDeletionAudit.class);

        verify(auditRepository).save(auditCaptor.capture());

        AccountDeletionAudit audit = auditCaptor.getValue();
        assertThat(audit.getUserId()).isEqualTo(USER_ID);
        assertThat(audit.getDeletedAt()).isEqualTo(user.getDeletedAt());
        assertThat(audit.getPurgeVersion()).isEqualTo(1);

        assertThat(user.isActive()).isFalse();
        assertThat(user.getDeletedAt()).isNotNull();
        assertThat(user.getFullName()).isNull();
        assertThat(user.getEmail()).isNull();
        assertThat(user.getPasswordHash()).isNull();
        assertThat(user.getWorkosUserId()).isNull();
        assertThat(user.getVisaType()).isNull();
    }

    @Test
    void repeatedPurgeCreatesOnlyOneAuditRecord() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(auditRepository.existsByUserId(USER_ID)).thenReturn(false, true);

        purgeService.purge(USER_ID);
        purgeService.purge(USER_ID);

        verify(auditRepository, times(1))
                .save(org.mockito.ArgumentMatchers.any(AccountDeletionAudit.class));
        verify(notificationStreamRegistry, times(2)).closeAll(USER_ID);
        verify(notificationRepository, times(2)).deleteAllByUserId(USER_ID);
        verify(userRepository, times(2)).save(user);
    }

    @Test
    void purgeOfMissingUserDoesNothing() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        purgeService.purge(USER_ID);

        verify(userRepository).findById(USER_ID);
        verify(notificationRepository, org.mockito.Mockito.never()).deleteAllByUserId(USER_ID);
        verify(notificationStreamRegistry, org.mockito.Mockito.never()).closeAll(USER_ID);
        verify(auditRepository, org.mockito.Mockito.never())
                .save(org.mockito.ArgumentMatchers.any(AccountDeletionAudit.class));
    }
}