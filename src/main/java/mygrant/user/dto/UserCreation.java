package mygrant.user.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class UserCreation {

    @NotBlank(message = "Full name is required")
    @Size(min = 2, max = 100, message = "Full name must be 2 to 100 characters")
    private String userName;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    @Size(max = 100, message = "Email must be at most 100 characters")
    private String userEmail;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    @Pattern(regexp = ".*[A-Za-z].*", message = "Password must contain a letter")
    @Pattern(regexp = ".*\\d.*", message = "Password must contain a number")
    private String userPassword;

    @NotBlank(message = "Visa type is required")
    @Size(max = 20, message = "Visa type must be at most 20 characters")
    private String visaType;      // e.g., "F-1", "J-1", "H-1B"
    private String academicLevel;   // e.g., "PhD", "Undergraduate", "Master's"
    private LocalDate programEndDate; // e.g., "2028-05-31"

    @Size(max = 20, message = "Current location must be at most 20 characters")
    private String currentLocation;       // e.g., "US", "ABROAD"
    private LocalDate upcomingTravelDate; // e.g., "2026-12-15"

    public UserCreation() {}

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }

    public String getUserPassword() {
        return userPassword;
    }

    public void setUserPassword(String userPassword) {
        this.userPassword = userPassword;
    }

    public String getVisaType() {
        return visaType;
    }

    public void setVisaType(String visaType) {
        this.visaType = visaType;
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

    public String getCurrentLocation() {
        return currentLocation;
    }

    public void setCurrentLocation(String currentLocation) {
        this.currentLocation = currentLocation;
    }

    public LocalDate getUpcomingTravelDate() {
        return upcomingTravelDate;
    }

    public void setUpcomingTravelDate(LocalDate upcomingTravelDate) {
        this.upcomingTravelDate = upcomingTravelDate;
    }
}