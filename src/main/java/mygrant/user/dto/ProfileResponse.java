package mygrant.user.dto;

import java.time.LocalDate;

import mygrant.user.UserProfile;

public record ProfileResponse(
        Long userId,
        int version,
        String visaType,
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
        LocalDate upcomingTravelDate
) {
    public static ProfileResponse from(UserProfile profile) {
        return new ProfileResponse(
                profile.getUserId(),
                profile.getVersion(),
                profile.getVisaType(),
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
                profile.getUpcomingTravelDate()
        );
    }
}