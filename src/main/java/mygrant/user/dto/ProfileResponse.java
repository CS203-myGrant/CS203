package mygrant.user.dto;

import java.time.LocalDate;
import java.time.Instant;
import java.util.Map;

import mygrant.user.UserProfile;

public record ProfileResponse(
        Long userId,
        int version,
        String visaType,
        String immigrantCategory,
        String chargeabilityArea,
        String countryOfOrigin,
        String countryOfCitizenship,
        String currentLocation,
        String employmentStatus,
        String employer,
        LocalDate visaStartDate,
        LocalDate visaExpiryDate,
        LocalDate optStartDate,
        LocalDate i140FilingDate,
        LocalDate priorityDate,
        String academicLevel,
        LocalDate programEndDate,
        LocalDate upcomingTravelDate,
        Map<String, Instant> consents
) {
    public static ProfileResponse from(UserProfile profile, Map<String, Instant> consents) {
        return new ProfileResponse(
                profile.getUserId(),
                profile.getVersion(),
                profile.getVisaType(),
                profile.getImmigrantCategory(),
                profile.getChargeabilityArea(),
                profile.getCountryOfOrigin(),
                profile.getCountryOfCitizenship(),
                profile.getCurrentLocation(),
                profile.getEmploymentStatus(),
                profile.getEmployer(),
                profile.getVisaStartDate(),
                profile.getVisaExpiryDate(),
                profile.getOptStartDate(),
                profile.getI140FilingDate(),
                profile.getPriorityDate(),
                profile.getAcademicLevel(),
                profile.getProgramEndDate(),
                profile.getUpcomingTravelDate(),
                Map.copyOf(consents)
        );
    }
}
