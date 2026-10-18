package mygrant.user.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ProfileCompletion {

    @NotBlank(message = "Visa type is required")
    @Size(max = 20, message = "Visa type must be at most 20 characters")
    private String visaType;
    private String academicLevel;
    private LocalDate programEndDate;
    private String currentLocation;
    private LocalDate upcomingTravelDate;

    public String getVisaType() { return visaType; }
    public void setVisaType(String visaType) { this.visaType = visaType; }
    public String getAcademicLevel() { return academicLevel; }
    public void setAcademicLevel(String academicLevel) { this.academicLevel = academicLevel; }
    public LocalDate getProgramEndDate() { return programEndDate; }
    public void setProgramEndDate(LocalDate programEndDate) { this.programEndDate = programEndDate; }
    public String getCurrentLocation() { return currentLocation; }
    public void setCurrentLocation(String currentLocation) { this.currentLocation = currentLocation; }
    public LocalDate getUpcomingTravelDate() { return upcomingTravelDate; }
    public void setUpcomingTravelDate(LocalDate upcomingTravelDate) { this.upcomingTravelDate = upcomingTravelDate; }
}
