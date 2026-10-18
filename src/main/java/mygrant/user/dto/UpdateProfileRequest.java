package mygrant.user.dto;

import java.time.LocalDate;

/*
 * Every field is optional.
 * A null field means the user did not send it, so its saved value stays unchanged.
 */
public record UpdateProfileRequest(
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
}