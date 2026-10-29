package mygrant.user;

public enum ProfileFieldDefinition {
    VISA_TYPE("visaType", true,
            "Used to identify immigration rules that may apply to your current status."),
    COUNTRY_OF_ORIGIN("countryOfOrigin", false,
            "Used only where a policy depends on country of birth or origin."),
    COUNTRY_OF_CITIZENSHIP("countryOfCitizenship", false,
            "Used only where a policy depends on nationality or citizenship."),
    CURRENT_LOCATION("currentLocation", false,
            "Used to identify rules affected by whether you are in the United States."),
    EMPLOYMENT_STATUS("employmentStatus", false,
            "Used to assess employment-related immigration changes."),
    EMPLOYER("employer", false,
            "Used to assess employer-specific immigration implications."),
    VISA_START_DATE("visaStartDate", false,
            "Used to assess dates and time spent in a visa category."),
    VISA_EXPIRY_DATE("visaExpiryDate", false,
            "Used to identify approaching visa-expiry deadlines."),
    OPT_START_DATE("optStartDate", false,
            "Used to assess OPT and STEM OPT timing."),
    I140_FILING_DATE("i140FilingDate", false,
            "Used to assess employment-based immigrant-petition timing."),
    PRIORITY_DATE("priorityDate", false,
            "Used to assess Visa Bulletin and immigrant-visa changes."),
    ACADEMIC_LEVEL("academicLevel", false,
            "Used to assess education-level rules and student eligibility."),
    PROGRAM_END_DATE("programEndDate", false,
            "Used to identify program-completion deadlines."),
    UPCOMING_TRAVEL_DATE("upcomingTravelDate", false,
            "Used to assess travel-related immigration changes."),
    IMMIGRANT_CATEGORY("immigrantCategory", false,
            "Used to assess employment-based immigrant-visa rules."),
    CHARGEABILITY_AREA("chargeabilityArea", false,
            "Used to assess Visa Bulletin chargeability rules.");

    private final String apiName;
    private final boolean required;
    private final String purpose;

    ProfileFieldDefinition(String apiName, boolean required, String purpose) {
        this.apiName = apiName;
        this.required = required;
        this.purpose = purpose;
    }

    public String apiName() { return apiName; }
    public boolean required() { return required; }
    public String purpose() { return purpose; }
}