package mygrant.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import mygrant.common.ProfileValidationException;
import mygrant.email.dto.EmailPreferencesRequest;
import mygrant.email.dto.EmailPreferencesResponse;
import mygrant.user.User;
import mygrant.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class EmailPreferenceServiceTests {

    private static final String EMAIL = "student@example.com";

    @Mock
    private EmailPreferenceRepository preferenceRepository;

    @Mock
    private UserRepository userRepository;

    @Test
    void returnsTheDefaultsUntilTheUserSavesAChoice() {
        User user = user(7L, "user_workos_7");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(preferenceRepository.findById(7L)).thenReturn(Optional.empty());

        EmailPreferencesResponse response = service().get(EMAIL);

        assertThat(response.criticalDelivery()).isEqualTo(DeliveryMode.IMMEDIATE);
        assertThat(response.otherDelivery()).isEqualTo(DeliveryMode.DIGEST);
        assertThat(response.email()).isEqualTo(EMAIL);
        assertThat(response.canReceiveEmail()).isTrue();
        assertThat(response.ineligibleReason()).isNull();
        verify(preferenceRepository, never()).save(any());
    }

    @Test
    void explainsWhyAnUnverifiedAccountGetsNoEmail() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user(7L, null)));
        when(preferenceRepository.findById(7L)).thenReturn(Optional.empty());

        EmailPreferencesResponse response = service().get(EMAIL);

        assertThat(response.canReceiveEmail()).isFalse();
        assertThat(response.ineligibleReason()).isEqualTo(IneligibleReason.EMAIL_NOT_VERIFIED);
    }

    @Test
    void savesBothModes() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user(7L, "user_workos_7")));
        when(preferenceRepository.findById(7L)).thenReturn(Optional.empty());
        when(preferenceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EmailPreferencesResponse response = service().update(EMAIL, new EmailPreferencesRequest("digest", " OFF "));

        ArgumentCaptor<EmailPreference> saved = ArgumentCaptor.forClass(EmailPreference.class);
        verify(preferenceRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(7L);
        assertThat(saved.getValue().getCriticalDelivery()).isEqualTo(DeliveryMode.DIGEST);
        assertThat(saved.getValue().getOtherDelivery()).isEqualTo(DeliveryMode.OFF);
        assertThat(response.criticalDelivery()).isEqualTo(DeliveryMode.DIGEST);
        assertThat(response.otherDelivery()).isEqualTo(DeliveryMode.OFF);
    }

    @Test
    void updatesAnExistingRowInPlace() {
        EmailPreference existing = new EmailPreference(7L, DeliveryMode.OFF, DeliveryMode.OFF);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user(7L, "user_workos_7")));
        when(preferenceRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(preferenceRepository.save(existing)).thenReturn(existing);

        service().update(EMAIL, new EmailPreferencesRequest("IMMEDIATE", "IMMEDIATE"));

        assertThat(existing.getCriticalDelivery()).isEqualTo(DeliveryMode.IMMEDIATE);
        assertThat(existing.getOtherDelivery()).isEqualTo(DeliveryMode.IMMEDIATE);
    }

    @Test
    void rejectsUnknownAndMissingModesWithFieldErrors() {
        assertThatThrownBy(() -> service().update(EMAIL, new EmailPreferencesRequest("WEEKLY", null)))
                .isInstanceOfSatisfying(ProfileValidationException.class, exception ->
                        assertThat(exception.getValidationErrors())
                                .containsOnlyKeys("criticalDelivery", "otherDelivery"));
        verify(preferenceRepository, never()).save(any());
    }

    @Test
    void unsubscribeTurnsBothModesOff() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(7L, "user_workos_7")));
        when(preferenceRepository.findById(7L)).thenReturn(Optional.empty());

        service().unsubscribe(7L);

        ArgumentCaptor<EmailPreference> saved = ArgumentCaptor.forClass(EmailPreference.class);
        verify(preferenceRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(7L);
        assertThat(saved.getValue().getCriticalDelivery()).isEqualTo(DeliveryMode.OFF);
        assertThat(saved.getValue().getOtherDelivery()).isEqualTo(DeliveryMode.OFF);
    }

    @Test
    void repeatingAnUnsubscribeChangesNothing() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(7L, "user_workos_7")));
        when(preferenceRepository.findById(7L))
                .thenReturn(Optional.of(new EmailPreference(7L, DeliveryMode.OFF, DeliveryMode.OFF)));

        service().unsubscribe(7L);

        verify(preferenceRepository, never()).save(any());
    }

    @Test
    void unsubscribeLeavesClosedAndMissingAccountsAlone() {
        User purged = user(7L, "user_workos_7");
        purged.anonymizeAndDeactivate(Instant.now());
        when(userRepository.findById(7L)).thenReturn(Optional.of(purged));
        when(userRepository.findById(8L)).thenReturn(Optional.empty());

        service().unsubscribe(7L);
        service().unsubscribe(8L);

        verify(preferenceRepository, never()).save(any());
    }

    private EmailPreferenceService service() {
        return new EmailPreferenceService(preferenceRepository, userRepository,
                new EmailEligibility(new EmailProperties("http://localhost:8080", false)));
    }

    private User user(Long id, String workosUserId) {
        User user = new User("Student", EMAIL, null, "F-1", null, null);
        ReflectionTestUtils.setField(user, "id", id);
        user.setWorkosUserId(workosUserId);
        return user;
    }
}
