package mygrant.user.dto;

import java.time.LocalDate;
import java.util.Set;

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
        LocalDate upcomingTravelDate,
        String immigrantCategory,
        String chargeabilityArea,
        Set<String> clearFields,
        Set<String> consentedFields,
        LocalDate i765FilingDate,
        LocalDate i539FilingDate

) {
    public UpdateProfileRequest(String visaType, String countryOfOrigin, String countryOfCitizenship,
            String currentLocation, String employmentStatus, String employer,
            LocalDate visaStartDate, LocalDate visaExpiryDate, LocalDate optStartDate,
            LocalDate i140FilingDate, LocalDate priorityDate, String academicLevel,
            LocalDate programEndDate, LocalDate upcomingTravelDate, String immigrantCategory,
            String chargeabilityArea, Set<String> clearFields, Set<String> consentedFields) {
        this(visaType, countryOfOrigin, countryOfCitizenship, currentLocation, employmentStatus,
                employer, visaStartDate, visaExpiryDate, optStartDate, i140FilingDate, priorityDate,
                academicLevel, programEndDate, upcomingTravelDate, immigrantCategory, chargeabilityArea,
                clearFields, consentedFields, null, null);
    }
    public UpdateProfileRequest(
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
        this(
                visaType, countryOfOrigin, countryOfCitizenship,
                currentLocation, employmentStatus, employer,
                visaStartDate, visaExpiryDate, optStartDate,
                i140FilingDate, priorityDate, academicLevel,
                programEndDate, upcomingTravelDate,
                null, null, Set.of(), Set.of(), null, null
        );
    }
}
