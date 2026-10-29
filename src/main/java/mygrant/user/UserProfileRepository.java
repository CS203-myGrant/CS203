package mygrant.user;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserProfileRepository
        extends JpaRepository<UserProfile, Long> {
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "DELETE FROM policy_recommendations WHERE user_id = :userId", nativeQuery = true)
    void deleteRecommendationsByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "DELETE FROM visa_application_progress WHERE user_id = :userId", nativeQuery = true)
    void deleteApplicationProgressByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);
}
