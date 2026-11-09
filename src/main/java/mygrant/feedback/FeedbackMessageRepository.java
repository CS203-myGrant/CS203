package mygrant.feedback;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedbackMessageRepository extends JpaRepository<FeedbackMessage, Long> {
    Page<FeedbackMessage> findByConversationIdOrderByCreatedAtAscIdAsc(Long conversationId, Pageable pageable);

    void deleteAllBySenderId(Long senderId);
}
