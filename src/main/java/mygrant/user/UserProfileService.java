package mygrant.user;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.time.Instant;
import java.util.stream.Collectors;

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
    private final ProfileFieldConsentRepository consentRepository;

    public UserProfileService(
            UserRepository userRepository,
            UserProfileRepository userProfileRepository,
            ProfileFieldConsentRepository consentRepository) {
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.consentRepository = consentRepository;
    }

    @Transactional(readOnly = true)
    public ProfileResponse getProfile(String email) {
        User user = currentUser(email);

        UserProfile profile = userProfileRepository.findById(user.getId())
                .orElseThrow(() ->
                        new IllegalStateException("Profile does not exist for authenticated user"));

        return response(profile);
    }

    @Transactional
    public ProfileResponse updateProfile(
            String email,
            UpdateProfileRequest request) {

        User user = currentUser(email);

        UserProfile profile = userProfileRepository.findById(user.getId())
        .orElseGet(() -> UserProfile.fromUser(user));
        applyUpdates(profile, request);
        clearWithdrawnFields(user.getId(), profile, request);
        validateOnboardingConsent(user, profile, request);
        validate(profile);

        recordConsents(user.getId(), profile, request);

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
        return response(savedProfile);
    }

    private ProfileResponse response(UserProfile profile) {
        Map<String, Instant> consents = consentRepository
                .findByUserId(profile.getUserId())
                .stream()
                .collect(Collectors.toUnmodifiableMap(
                        consent -> consent.getFieldName().name(),
                        ProfileFieldConsent::getConsentedAt
                ));

        return ProfileResponse.from(profile, consents);
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

        if (request.immigrantCategory() != null) {
            profile.setImmigrantCategory(normalizeOptional(request.immigrantCategory()));
        }

        if (request.chargeabilityArea() != null) {
            profile.setChargeabilityArea(normalizeOptional(request.chargeabilityArea()));
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
            String currentLocation = request.currentLocation().trim();
            profile.setCurrentLocation(
                    currentLocation.isEmpty() ? null : currentLocation
            );
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

    private void clearWithdrawnFields(
        Long userId,
        UserProfile profile,
        UpdateProfileRequest request) {

        Set<String> clearFields = request.clearFields() == null
                ? Set.of()
                : request.clearFields();

        for (String fieldName : clearFields) {
            ProfileFieldDefinition field = profileField(fieldName, "clearFields");

            if (field.required()) {
                throw new ProfileValidationException(Map.of(
                        field.apiName(), "This required field cannot be withdrawn"
                ));
            }

            clearProfileField(profile, field);

            consentRepository.deleteByUserIdAndFieldName(userId, field);
        }
    }

    private void clearProfileField(UserProfile profile, ProfileFieldDefinition field) {
        switch (field) {
            case COUNTRY_OF_ORIGIN -> profile.setCountryOfOrigin(null);
            case COUNTRY_OF_CITIZENSHIP -> profile.setCountryOfCitizenship(null);
            case CURRENT_LOCATION -> profile.setCurrentLocation(null);
            case EMPLOYMENT_STATUS -> profile.setEmploymentStatus(null);
            case EMPLOYER -> profile.setEmployer(null);
            case VISA_START_DATE -> profile.setVisaStartDate(null);
            case VISA_EXPIRY_DATE -> profile.setVisaExpiryDate(null);
            case OPT_START_DATE -> profile.setOptStartDate(null);
            case I140_FILING_DATE -> profile.setI140FilingDate(null);
            case PRIORITY_DATE -> profile.setPriorityDate(null);
            case ACADEMIC_LEVEL -> profile.setAcademicLevel(null);
            case PROGRAM_END_DATE -> profile.setProgramEndDate(null);
            case UPCOMING_TRAVEL_DATE -> profile.setUpcomingTravelDate(null);
            case IMMIGRANT_CATEGORY -> profile.setImmigrantCategory(null);
            case CHARGEABILITY_AREA -> profile.setChargeabilityArea(null);
            case VISA_TYPE -> throw new IllegalArgumentException("visaType is required");
        }
    }

    private void recordConsents(
            Long userId,
            UserProfile profile,
            UpdateProfileRequest request) {

        Set<String> consentedFields = request.consentedFields() == null
                ? Set.of()
                : request.consentedFields();

        for (String fieldName : consentedFields) {
            ProfileFieldDefinition field = profileField(fieldName, "consentedFields");

            if (!isPopulated(profile, field)) {
                continue;
            }

            consentRepository.save(new ProfileFieldConsent(
                    userId,
                    field,
                    java.time.Instant.now()
            ));
        }
    }

    private ProfileFieldDefinition profileField(String fieldName, String requestField) {
        try {
            return ProfileFieldDefinition.valueOf(fieldName);
        } catch (IllegalArgumentException exception) {
            throw new ProfileValidationException(Map.of(
                    requestField,
                    "Unknown profile field: " + fieldName
            ));
        }
    }

    private boolean isPopulated(UserProfile profile, ProfileFieldDefinition field) {
        return switch (field) {
            case VISA_TYPE -> isPresent(profile.getVisaType());
            case COUNTRY_OF_ORIGIN -> isPresent(profile.getCountryOfOrigin());
            case COUNTRY_OF_CITIZENSHIP -> isPresent(profile.getCountryOfCitizenship());
            case CURRENT_LOCATION -> isPresent(profile.getCurrentLocation());
            case EMPLOYMENT_STATUS -> isPresent(profile.getEmploymentStatus());
            case EMPLOYER -> isPresent(profile.getEmployer());
            case VISA_START_DATE -> profile.getVisaStartDate() != null;
            case VISA_EXPIRY_DATE -> profile.getVisaExpiryDate() != null;
            case OPT_START_DATE -> profile.getOptStartDate() != null;
            case I140_FILING_DATE -> profile.getI140FilingDate() != null;
            case PRIORITY_DATE -> profile.getPriorityDate() != null;
            case ACADEMIC_LEVEL -> isPresent(profile.getAcademicLevel());
            case PROGRAM_END_DATE -> profile.getProgramEndDate() != null;
            case UPCOMING_TRAVEL_DATE -> profile.getUpcomingTravelDate() != null;
            case IMMIGRANT_CATEGORY -> isPresent(profile.getImmigrantCategory());
            case CHARGEABILITY_AREA -> isPresent(profile.getChargeabilityArea());
        };
    }

    private void validateOnboardingConsent(
            User user,
            UserProfile profile,
            UpdateProfileRequest request) {
        if (user.isProfileComplete()) {
            return;
        }

        Set<String> consentedFields = request.consentedFields() == null
                ? Set.of()
                : request.consentedFields();
        Map<String, String> errors = new LinkedHashMap<>();

        for (ProfileFieldDefinition field : ProfileFieldDefinition.values()) {
            if (isPopulated(profile, field) && !consentedFields.contains(field.name())) {
                errors.put(field.apiName(), "Consent is required before this field can be used.");
            }
        }

        if (!errors.isEmpty()) {
            throw new ProfileValidationException(errors);
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

        boolean hasImmigrantField = isPresent(profile.getImmigrantCategory())
                || isPresent(profile.getChargeabilityArea())
                || profile.getPriorityDate() != null;
        if (hasImmigrantField) {
            if (!isPresent(profile.getImmigrantCategory())) {
                errors.put("immigrantCategory", "Required with chargeabilityArea and priorityDate");
            }
            if (!isPresent(profile.getChargeabilityArea())) {
                errors.put("chargeabilityArea", "Required with immigrantCategory and priorityDate");
            }
            if (profile.getPriorityDate() == null) {
                errors.put("priorityDate", "Required with immigrantCategory and chargeabilityArea");
            }
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

    private String normalizeOptional(String value) {
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
