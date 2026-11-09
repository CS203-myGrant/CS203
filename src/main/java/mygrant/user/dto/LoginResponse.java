package mygrant.user.dto;

import java.time.LocalDate;

import mygrant.user.User;
import mygrant.user.UserRole;

public class LoginResponse {

    private Long userId;
    private String userName;
    private String userEmail;
    private String visaType;
    private String academicLevel;
    private LocalDate programEndDate;
    private String currentLocation;
    private LocalDate upcomingTravelDate;
    private String token;
    private String refreshToken;
    private UserRole role;
    private boolean profileComplete;

    public LoginResponse() {}

    // Constructor to quickly map from the User entity
    public LoginResponse(User user) {
        this.userId = user.getId();
        this.userName = user.getFullName();
        this.userEmail = user.getEmail();
        this.visaType = user.getVisaType();
        this.academicLevel = user.getAcademicLevel();
        this.programEndDate = user.getProgramEndDate();
        this.currentLocation = user.getCurrentLocation();
        this.upcomingTravelDate = user.getUpcomingTravelDate();
        this.role = user.getRole();
        this.profileComplete = user.isProfileComplete();
    }

    public LoginResponse(User user, String token) {
        this(user);
        this.token = token;
    }

    public LoginResponse(User user, String token, String refreshToken) {
        this(user, token);
        this.refreshToken = refreshToken;
    }

    // Getters and Setters

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

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

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public boolean isProfileComplete() {
        return profileComplete;
    }

    public void setProfileComplete(boolean profileComplete) {
        this.profileComplete = profileComplete;
    }
}
