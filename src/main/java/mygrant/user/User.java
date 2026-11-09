package mygrant.user;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", length = 100)
    private String fullName;

    @Column(name = "email", unique = true, length = 100)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "workos_user_id", unique = true, length = 100)
    private String workosUserId;

    @Column(name = "visa_type", length = 20)
    private String visaType; // e.g., "F-1", "J-1", "H-1B"

    @Column(name = "academic_level", length = 50)
    private String academicLevel; // e.g., "PhD", "Undergraduate", "Master's"

    @Column(name = "program_end_date")
    private LocalDate programEndDate;

    @Column(name = "current_location", length = 20)
    private String currentLocation; // e.g., "US", "ABROAD"

    @Column(name = "upcoming_travel_date")
    private LocalDate upcomingTravelDate;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role = UserRole.APPLICANT;

    @Column(name = "profile_complete", nullable = false)
    private boolean profileComplete = true;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    // Default Constructor for JPA
    public User() {}

    public User(String fullName, String email, String passwordHash, String visaType, String academicLevel, LocalDate programEndDate) {
        this.fullName = fullName;
        this.email = email;
        this.passwordHash = passwordHash;
        this.visaType = visaType;
        this.academicLevel = academicLevel;
        this.programEndDate = programEndDate;
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getWorkosUserId() {
        return workosUserId;
    }

    public void setWorkosUserId(String workosUserId) {
        this.workosUserId = workosUserId;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
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

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    /** Clears identifying and profile values while retaining an inactive account tombstone. */
    public void anonymizeAndDeactivate(Instant deletedAt) {
        this.fullName = null;
        this.email = null;
        this.passwordHash = null;
        this.workosUserId = null;
        this.visaType = null;
        this.academicLevel = null;
        this.programEndDate = null;
        this.currentLocation = null;
        this.upcomingTravelDate = null;
        this.profileComplete = false;
        this.active = false;
        if (this.deletedAt == null) {
            this.deletedAt = deletedAt;
        }
    }
}
