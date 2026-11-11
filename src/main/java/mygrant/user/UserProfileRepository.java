package mygrant.user;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserProfileRepository
        extends JpaRepository<UserProfile, Long> {
    /** Exact matching after normalising case and surrounding whitespace. */
    @org.springframework.data.jpa.repository.Query(
            "select p from UserProfile p where upper(trim(p.visaType)) in :visaTypes")
    java.util.List<UserProfile> findMatchingVisaTypes(
            @org.springframework.data.repository.query.Param("visaTypes") java.util.Collection<String> visaTypes);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "DELETE FROM policy_recommendations WHERE user_id = :userId", nativeQuery = true)
    void deleteRecommendationsByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "DELETE FROM visa_application_progress WHERE user_id = :userId", nativeQuery = true)
    void deleteApplicationProgressByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value="DELETE FROM policy_question_history WHERE user_id = :userId",nativeQuery=true)
    void deleteQuestionHistoryByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);
}
