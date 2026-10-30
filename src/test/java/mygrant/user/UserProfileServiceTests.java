package mygrant.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;


import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;
import java.util.List;
import java.util.Set;


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

    @Mock
    private ProfileFieldConsentRepository consentRepository;

    @Captor
    private ArgumentCaptor<ProfileFieldConsent> consentCaptor;

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
    void createsAProfileWithOnlyTheRequiredVisaTypeAndItsConsent() {
        User user = user();
        user.setVisaType(null);
        user.setAcademicLevel(null);
        user.setProgramEndDate(null);
        user.setCurrentLocation(null);
        user.setUpcomingTravelDate(null);
        user.setProfileComplete(false);

        when(userRepository.findByEmail(user.getEmail()))
                .thenReturn(Optional.of(user));
        when(userProfileRepository.findById(user.getId()))
                .thenReturn(Optional.empty());
        when(userProfileRepository.save(any(UserProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UpdateProfileRequest request = new UpdateProfileRequest(
                "F-1", null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null,
                Set.of(), Set.of("VISA_TYPE")
        );

        ProfileResponse response = service().updateProfile(user.getEmail(), request);

        assertThat(response.visaType()).isEqualTo("F-1");
        assertThat(response.countryOfOrigin()).isNull();
        assertThat(response.employer()).isNull();
        assertThat(user.isProfileComplete()).isTrue();
        verify(consentRepository).save(consentCaptor.capture());
        assertThat(consentCaptor.getValue().getFieldName())
                .isEqualTo(ProfileFieldDefinition.VISA_TYPE);
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
                "EB-2", null, Set.of(), Set.of());

        assertThatThrownBy(() -> service().updateProfile(user.getEmail(), request))
                .isInstanceOf(ProfileValidationException.class)
                .extracting(error -> ((ProfileValidationException) error).getValidationErrors())
                .isEqualTo(Map.of(
                        "chargeabilityArea", "Required with immigrantCategory and priorityDate"));
    }

    @Test
    void clearsAnOptionalFieldAndDeletesItsConsent() {
        User user = user();
        UserProfile profile = profile(user);
        profile.setEmployer("Example Inc.");

        when(userRepository.findByEmail(user.getEmail()))
                .thenReturn(Optional.of(user));
        when(userProfileRepository.findById(user.getId()))
                .thenReturn(Optional.of(profile));
        when(userProfileRepository.save(any(UserProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UpdateProfileRequest request = new UpdateProfileRequest(
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null,
                Set.of("EMPLOYER"),
                Set.of()
        );

        ProfileResponse response = service().updateProfile(user.getEmail(), request);

        assertThat(response.employer()).isNull();
        verify(consentRepository).deleteByUserIdAndFieldName(
                user.getId(),
                ProfileFieldDefinition.EMPLOYER
        );
    }

    private UserProfileService service() {
        return new UserProfileService(
                userRepository,
                userProfileRepository,
                consentRepository
        );
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

    @Test
    void rejectsWithdrawalOfRequiredVisaType() {
        User user = user();
        UserProfile profile = profile(user);

        when(userRepository.findByEmail(user.getEmail()))
                .thenReturn(Optional.of(user));
        when(userProfileRepository.findById(user.getId()))
                .thenReturn(Optional.of(profile));

        UpdateProfileRequest request = new UpdateProfileRequest(
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null,
                Set.of("VISA_TYPE"),
                Set.of()
        );

        assertThatThrownBy(() -> service().updateProfile(user.getEmail(), request))
                .isInstanceOf(ProfileValidationException.class)
                .extracting(error -> ((ProfileValidationException) error)
                        .getValidationErrors())
                .isEqualTo(Map.of(
                        "visaType",
                        "This required field cannot be withdrawn"
        ));
    }

    @Test
    void recordsConsentForAPopulatedField() {
        User user = user();
        UserProfile profile = profile(user);
        profile.setCountryOfCitizenship("Malaysia");

        when(userRepository.findByEmail(user.getEmail()))
                .thenReturn(Optional.of(user));
        when(userProfileRepository.findById(user.getId()))
                .thenReturn(Optional.of(profile));
        when(userProfileRepository.save(any(UserProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UpdateProfileRequest request = new UpdateProfileRequest(
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null,
                Set.of(),
                Set.of("COUNTRY_OF_CITIZENSHIP")
        );

        service().updateProfile(user.getEmail(), request);

        verify(consentRepository).save(consentCaptor.capture());

        ProfileFieldConsent savedConsent = consentCaptor.getValue();
        assertThat(savedConsent.getFieldName())
                .isEqualTo(ProfileFieldDefinition.COUNTRY_OF_CITIZENSHIP);
        assertThat(savedConsent.getConsentedAt()).isNotNull();
    }

    @Test
    void rejectsIncompleteProfileWithoutConsentForVisaType() {
        User user = user();
        user.setProfileComplete(false);
        UserProfile profile = profile(user);

        when(userRepository.findByEmail(user.getEmail()))
                .thenReturn(Optional.of(user));
        when(userProfileRepository.findById(user.getId()))
                .thenReturn(Optional.of(profile));

        UpdateProfileRequest request = new UpdateProfileRequest(
                "F-1", null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, Set.of(), Set.of()
        );

        assertThatThrownBy(() -> service().updateProfile(user.getEmail(), request))
                .isInstanceOf(ProfileValidationException.class)
                .extracting(error -> ((ProfileValidationException) error).getValidationErrors())
                .isEqualTo(Map.of(
                        "visaType", "Consent is required before this field can be used.",
                        "academicLevel", "Consent is required before this field can be used.",
                        "programEndDate", "Consent is required before this field can be used."
                ));
    }

}
