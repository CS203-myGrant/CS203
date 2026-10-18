package mygrant.feedback;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedbackConversationRepository extends JpaRepository<FeedbackConversation, Long> {
    Optional<FeedbackConversation> findFirstByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status);
    List<FeedbackConversation> findAllByOrderByUpdatedAtDesc();

    void deleteAllByUserId(Long userId);
}
