package mygrant.user;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_profiles")
public class UserProfile {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private int version = 1;

    @Column(name = "visa_type", nullable = false, length = 20)
    private String visaType;

    @Column(name = "immigrant_category", length = 30)
    private String immigrantCategory;

    @Column(name = "chargeability_area", length = 100)
    private String chargeabilityArea;

    @Column(name = "country_of_origin", length = 100)
    private String countryOfOrigin;

    @Column(name = "country_of_citizenship", length = 100)
    private String countryOfCitizenship;

    @Column(name = "current_location", length = 20)
    private String currentLocation;

    @Column(name = "employment_status", length = 30)
    private String employmentStatus;

    @Column(length = 255)
    private String employer;

    @Column(name = "visa_start_date")
    private LocalDate visaStartDate;

    @Column(name = "visa_expiry_date")
    private LocalDate visaExpiryDate;

    @Column(name = "opt_start_date")
    private LocalDate optStartDate;

    @Column(name = "i140_filing_date")
    private LocalDate i140FilingDate;

    @Column(name = "priority_date")
    private LocalDate priorityDate;

    @Column(name = "academic_level", length = 50)
    private String academicLevel;

    @Column(name = "program_end_date")
    private LocalDate programEndDate;

    @Column(name = "upcoming_travel_date")
    private LocalDate upcomingTravelDate;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected UserProfile() {
    }

    public static UserProfile fromUser(User user) {
        UserProfile profile = new UserProfile();

        profile.setUserId(user.getId());
        profile.setVisaType(user.getVisaType());
        profile.setAcademicLevel(user.getAcademicLevel());
        profile.setProgramEndDate(user.getProgramEndDate());
        profile.setCurrentLocation(user.getCurrentLocation());
        profile.setUpcomingTravelDate(user.getUpcomingTravelDate());

        return profile;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getVisaType() {
        return visaType;
    }

    public void setVisaType(String visaType) {
        this.visaType = visaType;
    }

    public String getImmigrantCategory() {
        return immigrantCategory;
    }

    public void setImmigrantCategory(String immigrantCategory) {
        this.immigrantCategory = immigrantCategory;
    }

    public String getChargeabilityArea() {
        return chargeabilityArea;
    }

    public void setChargeabilityArea(String chargeabilityArea) {
        this.chargeabilityArea = chargeabilityArea;
    }

    public String getCountryOfOrigin() {
        return countryOfOrigin;
    }

    public void setCountryOfOrigin(String countryOfOrigin) {
        this.countryOfOrigin = countryOfOrigin;
    }

    public String getCountryOfCitizenship() {
        return countryOfCitizenship;
    }

    public void setCountryOfCitizenship(String countryOfCitizenship) {
        this.countryOfCitizenship = countryOfCitizenship;
    }

    public String getCurrentLocation() {
        return currentLocation;
    }

    public void setCurrentLocation(String currentLocation) {
        this.currentLocation = currentLocation;
    }

    public String getEmploymentStatus() {
        return employmentStatus;
    }

    public void setEmploymentStatus(String employmentStatus) {
        this.employmentStatus = employmentStatus;
    }

    public String getEmployer() {
        return employer;
    }

    public void setEmployer(String employer) {
        this.employer = employer;
    }

    public LocalDate getVisaStartDate() {
        return visaStartDate;
    }

    public void setVisaStartDate(LocalDate visaStartDate) {
        this.visaStartDate = visaStartDate;
    }

    public LocalDate getVisaExpiryDate() {
        return visaExpiryDate;
    }

    public void setVisaExpiryDate(LocalDate visaExpiryDate) {
        this.visaExpiryDate = visaExpiryDate;
    }

    public LocalDate getOptStartDate() {
        return optStartDate;
    }

    public void setOptStartDate(LocalDate optStartDate) {
        this.optStartDate = optStartDate;
    }

    public LocalDate getI140FilingDate() {
        return i140FilingDate;
    }

    public void setI140FilingDate(LocalDate i140FilingDate) {
        this.i140FilingDate = i140FilingDate;
    }

    public LocalDate getPriorityDate() {
        return priorityDate;
    }

    public void setPriorityDate(LocalDate priorityDate) {
        this.priorityDate = priorityDate;
    }

    public String getAcademicLevel() {
        return academicLevel;
    }

    public void setAcademicLevel(String academicLevel) {
        this.academicLevel = academicLevel;
    }

    public LocalDate getProgramEndDate() {
        return programEndDate;
    }

    public void setProgramEndDate(LocalDate programEndDate) {
        this.programEndDate = programEndDate;
    }

    public LocalDate getUpcomingTravelDate() {
        return upcomingTravelDate;
    }

    public void setUpcomingTravelDate(LocalDate upcomingTravelDate) {
        this.upcomingTravelDate = upcomingTravelDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
