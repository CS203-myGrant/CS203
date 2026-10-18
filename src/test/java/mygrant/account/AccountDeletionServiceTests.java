package mygrant.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import mygrant.account.dto.DeletionRequestResponse;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.workos.WorkOsService;

@ExtendWith(MockitoExtension.class)
class AccountDeletionServiceTests {

    private static final Long USER_ID = 7L;
    private static final String EMAIL = "student@example.com";

    @Mock
    private UserRepository userRepository;

    @Mock
    private AccountDeletionRequestRepository deletionRequestRepository;

    @Mock
    private AccountPurgeService accountPurgeService;

    @Mock
    private WorkOsService workOsService;

    @Captor
    private ArgumentCaptor<AccountDeletionRequest> deletionRequestCaptor;

    private User user;
    private AccountDeletionService service;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(USER_ID);
        user.setEmail(EMAIL);

        service = new AccountDeletionService(
                userRepository,
                deletionRequestRepository,
                accountPurgeService,
                workOsService);
    }

    @Test
    void requestCreatesChallengeButDoesNotDeleteAnything() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        DeletionRequestResponse response = service.requestDeletion(EMAIL);

        assertThat(response.challenge()).hasSize(43);
        assertThat(response.expiresAt()).isAfter(Instant.now());

        verify(deletionRequestRepository)
                .deleteAllByUserIdAndConfirmedAtIsNull(USER_ID);
        verify(deletionRequestRepository).save(deletionRequestCaptor.capture());

        AccountDeletionRequest savedRequest = deletionRequestCaptor.getValue();
        assertThat(savedRequest.getUserId()).isEqualTo(USER_ID);
        assertThat(savedRequest.getChallengeHash()).hasSize(64);
        assertThat(savedRequest.getExpiresAt()).isAfter(Instant.now());

        verifyNoInteractions(accountPurgeService, workOsService);
    }

    @Test
    void expiredChallengeIsRejectedWithoutPurgingOrDeletingWorkOsUser() {
        AccountDeletionRequest expiredRequest = new AccountDeletionRequest(
                USER_ID,
                "a".repeat(64),
                Instant.now().minusSeconds(1));

        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(deletionRequestRepository.findByChallengeHashAndUserId(anyString(), eq(USER_ID)))
                .thenReturn(Optional.of(expiredRequest));

        assertThatThrownBy(() -> service.confirmDeletion(EMAIL, "x".repeat(43)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(accountPurgeService, workOsService);
    }

    @Test
    void confirmedChallengeCannotBeReused() {
        AccountDeletionRequest request = new AccountDeletionRequest(
                USER_ID,
                "a".repeat(64),
                Instant.now().plusSeconds(900));

        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(deletionRequestRepository.findByChallengeHashAndUserId(anyString(), eq(USER_ID)))
                .thenReturn(Optional.of(request));

        service.confirmDeletion(EMAIL, "x".repeat(43));

        assertThat(request.getConfirmedAt()).isNotNull();
        verify(accountPurgeService).purge(USER_ID);

        assertThatThrownBy(() -> service.confirmDeletion(EMAIL, "x".repeat(43)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(accountPurgeService, times(1)).purge(USER_ID);
        verifyNoInteractions(workOsService);
    }

    @Test
    void workOsFailurePreventsLocalPurge() {
        user.setWorkosUserId("user_workos_123");

        AccountDeletionRequest request = new AccountDeletionRequest(
                USER_ID,
                "a".repeat(64),
                Instant.now().plusSeconds(900));

        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(deletionRequestRepository.findByChallengeHashAndUserId(anyString(), eq(USER_ID)))
                .thenReturn(Optional.of(request));

        RuntimeException workOsFailure = new RuntimeException("WorkOS is unavailable");
        org.mockito.Mockito.doThrow(workOsFailure)
                .when(workOsService)
                .deleteUser("user_workos_123");

        assertThatThrownBy(() -> service.confirmDeletion(EMAIL, "x".repeat(43)))
                .isSameAs(workOsFailure);

        verify(workOsService).deleteUser("user_workos_123");
        verify(accountPurgeService, never()).purge(USER_ID);
    }

    @Test
    void unknownUserCannotRequestDeletion() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requestDeletion(EMAIL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Authenticated user no longer exists");

        verifyNoInteractions(deletionRequestRepository, accountPurgeService, workOsService);
    }
}