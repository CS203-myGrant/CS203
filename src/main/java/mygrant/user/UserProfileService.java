package mygrant.user;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mygrant.common.ProfileValidationException;
import mygrant.user.dto.ProfileResponse;
import mygrant.user.dto.UpdateProfileRequest;

@Service
public class UserProfileService {

    private static final Set<String> SUPPORTED_VISA_TYPES =
            Set.of("F-1", "J-1", "H-1B");

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;

    public UserProfileService(
            UserRepository userRepository,
            UserProfileRepository userProfileRepository) {
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
    }

    @Transactional(readOnly = true)
    public ProfileResponse getProfile(String email) {
        User user = currentUser(email);

        UserProfile profile = userProfileRepository.findById(user.getId())
                .orElseThrow(() ->
                        new IllegalStateException("Profile does not exist for authenticated user"));

        return ProfileResponse.from(profile);
    }

    @Transactional
    public ProfileResponse updateProfile(
            String email,
            UpdateProfileRequest request) {

        User user = currentUser(email);

        UserProfile profile = userProfileRepository.findById(user.getId())
        .orElseGet(() -> UserProfile.fromUser(user));

        applyUpdates(profile, request);
        validate(profile);

        profile.setVersion(profile.getVersion() + 1);
        user.setProfileComplete(true);

        // Keep legacy user fields in sync until alerts are migrated to user_profiles.
        user.setVisaType(profile.getVisaType());
        user.setAcademicLevel(profile.getAcademicLevel());
        user.setProgramEndDate(profile.getProgramEndDate());
        user.setCurrentLocation(profile.getCurrentLocation());
        user.setUpcomingTravelDate(profile.getUpcomingTravelDate());

        userRepository.save(user);

        UserProfile savedProfile = userProfileRepository.save(profile);
        return ProfileResponse.from(savedProfile);
    }

    private User currentUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new IllegalStateException("Authenticated user no longer exists"));
    }

    private void applyUpdates(
            UserProfile profile,
            UpdateProfileRequest request) {

        if (request.visaType() != null) {
            profile.setVisaType(normalizeVisaType(request.visaType()));
        }

        if (request.countryOfOrigin() != null) {
            profile.setCountryOfOrigin(request.countryOfOrigin().trim());
        }

        if (request.countryOfCitizenship() != null) {
            profile.setCountryOfCitizenship(
                    request.countryOfCitizenship().trim()
            );
        }

        if (request.currentLocation() != null) {
            profile.setCurrentLocation(request.currentLocation().trim());
        }

        if (request.employmentStatus() != null) {
            profile.setEmploymentStatus(request.employmentStatus().trim());
        }

        if (request.employer() != null) {
            profile.setEmployer(request.employer().trim());
        }

        if (request.visaStartDate() != null) {
            profile.setVisaStartDate(request.visaStartDate());
        }

        if (request.visaExpiryDate() != null) {
            profile.setVisaExpiryDate(request.visaExpiryDate());
        }

        if (request.optStartDate() != null) {
            profile.setOptStartDate(request.optStartDate());
        }

        if (request.i140FilingDate() != null) {
            profile.setI140FilingDate(request.i140FilingDate());
        }

        if (request.priorityDate() != null) {
            profile.setPriorityDate(request.priorityDate());
        }

        if (request.academicLevel() != null) {
            profile.setAcademicLevel(request.academicLevel().trim());
        }

        if (request.programEndDate() != null) {
            profile.setProgramEndDate(request.programEndDate());
        }

        if (request.upcomingTravelDate() != null) {
            profile.setUpcomingTravelDate(request.upcomingTravelDate());
        }
    }

    private void validate(UserProfile profile) {
        Map<String, String> errors = new LinkedHashMap<>();

        if (!SUPPORTED_VISA_TYPES.contains(profile.getVisaType())) {
            errors.put(
                    "visaType",
                    "Must be one of F-1, J-1, or H-1B"
            );
        }

        if (profile.getVisaStartDate() != null
                && profile.getVisaExpiryDate() != null
                && profile.getVisaExpiryDate()
                        .isBefore(profile.getVisaStartDate())) {
            errors.put(
                    "visaExpiryDate",
                    "Must be on or after visaStartDate"
            );
        }

        if (!errors.isEmpty()) {
            throw new ProfileValidationException(errors);
        }
    }

    private String normalizeVisaType(String visaType) {
        return visaType.trim().toUpperCase();
    }
}