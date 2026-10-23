package mygrant.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import mygrant.common.ProfileValidationException;
import mygrant.user.dto.ProfileResponse;
import mygrant.user.dto.UpdateProfileRequest;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTests {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserProfileRepository userProfileRepository;

    @Captor
    private ArgumentCaptor<UserProfile> profileCaptor;

    @Captor
    private ArgumentCaptor<User> userCaptor;

    @Test
    void updatesOnlySuppliedFieldsAndIncrementsVersion() {
        User user = user();
        UserProfile profile = profile(user);

        when(userRepository.findByEmail(user.getEmail()))
                .thenReturn(Optional.of(user));
        when(userProfileRepository.findById(user.getId()))
                .thenReturn(Optional.of(profile));
        when(userProfileRepository.save(any(UserProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UpdateProfileRequest request = new UpdateProfileRequest(
                " h-1b ",
                null,
                "Malaysia",
                null,
                "Employed",
                "Example Inc.",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        ProfileResponse response = service().updateProfile(user.getEmail(), request);

        verify(userProfileRepository).save(profileCaptor.capture());
        verify(userRepository).save(userCaptor.capture());

        assertThat(response.visaType()).isEqualTo("H-1B");
        assertThat(response.countryOfCitizenship()).isEqualTo("Malaysia");
        assertThat(response.employer()).isEqualTo("Example Inc.");
        assertThat(response.academicLevel()).isEqualTo("Master's");
        assertThat(response.version()).isEqualTo(2);

        assertThat(userCaptor.getValue().getVisaType()).isEqualTo("H-1B");
        assertThat(userCaptor.getValue().isProfileComplete()).isTrue();
    }

    @Test
    void rejectsVisaExpiryBeforeVisaStart() {
        User user = user();
        UserProfile profile = profile(user);

        when(userRepository.findByEmail(user.getEmail()))
                .thenReturn(Optional.of(user));
        when(userProfileRepository.findById(user.getId()))
                .thenReturn(Optional.of(profile));

        UpdateProfileRequest request = new UpdateProfileRequest(
                null, null, null, null, null, null,
                LocalDate.parse("2027-08-15"),
                LocalDate.parse("2027-08-14"),
                null, null, null, null, null, null
        );

        assertThatThrownBy(() -> service().updateProfile(user.getEmail(), request))
                .isInstanceOf(ProfileValidationException.class)
                .extracting(error -> ((ProfileValidationException) error)
                        .getValidationErrors())
                .isEqualTo(Map.of(
                        "visaExpiryDate",
                        "Must be on or after visaStartDate"
                ));
    }

    @Test
    void createsProfileWhenAWorkOsUserCompletesItForTheFirstTime() {
        User user = user();
        user.setVisaType(null);
        user.setProfileComplete(false);

        when(userRepository.findByEmail(user.getEmail()))
                .thenReturn(Optional.of(user));
        when(userProfileRepository.findById(user.getId()))
                .thenReturn(Optional.empty());
        when(userProfileRepository.save(any(UserProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UpdateProfileRequest request = new UpdateProfileRequest(
                "F-1",
                "Malaysia",
                "Malaysia",
                "US",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "Master's",
                LocalDate.parse("2028-05-31"),
                null
        );

        ProfileResponse response = service().updateProfile(user.getEmail(), request);

        assertThat(response.visaType()).isEqualTo("F-1");
        assertThat(user.isProfileComplete()).isTrue();
        verify(userProfileRepository).save(profileCaptor.capture());
        assertThat(profileCaptor.getValue().getUserId()).isEqualTo(user.getId());
    }

    @Test
    void requiresVisaBulletinFieldsTogether() {
        User user = user();
        UserProfile profile = profile(user);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(userProfileRepository.findById(user.getId())).thenReturn(Optional.of(profile));

        UpdateProfileRequest request = new UpdateProfileRequest(
                null, null, null, null, null, null, null, null, null, null,
                LocalDate.parse("2026-01-01"), null, null, null,
                "EB-2", null);

        assertThatThrownBy(() -> service().updateProfile(user.getEmail(), request))
                .isInstanceOf(ProfileValidationException.class)
                .extracting(error -> ((ProfileValidationException) error).getValidationErrors())
                .isEqualTo(Map.of(
                        "chargeabilityArea", "Required with immigrantCategory and priorityDate"));
    }

    private UserProfileService service() {
        return new UserProfileService(userRepository, userProfileRepository);
    }

    private User user() {
        User user = new User();
        user.setId(1L);
        user.setEmail("student@example.com");
        user.setVisaType("F-1");
        user.setAcademicLevel("Master's");
        user.setProgramEndDate(LocalDate.parse("2028-05-31"));
        return user;
    }

    private UserProfile profile(User user) {
        return UserProfile.fromUser(user);
    }

    @Test
    void clearsCurrentLocationWhenAnEmptyStringIsSupplied() {
    User user = user();
    user.setCurrentLocation("US");
    UserProfile profile = profile(user);

    when(userRepository.findByEmail(user.getEmail()))
        .thenReturn(Optional.of(user));
    when(userProfileRepository.findById(user.getId()))
        .thenReturn(Optional.of(profile));
    when(userProfileRepository.save(any(UserProfile.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    UpdateProfileRequest request = new UpdateProfileRequest(
        null, null, null, "",
        null, null, null, null, null, null, null,
        null, null, null
    );

    ProfileResponse response = service().updateProfile(user.getEmail(), request);

    assertThat(response.currentLocation()).isNull();
    assertThat(user.getCurrentLocation()).isNull();
    }
}
