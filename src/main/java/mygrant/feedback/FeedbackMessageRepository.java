<<<<<<< HEAD
package mygrant.feedback;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedbackMessageRepository extends JpaRepository<FeedbackMessage, Long> {
    List<FeedbackMessage> findByConversationIdOrderByCreatedAtAscIdAsc(Long conversationId);

    void deleteAllBySenderId(Long senderId);
}
=======
package mygrant.feedback;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedbackMessageRepository extends JpaRepository<FeedbackMessage, Long> {
    Page<FeedbackMessage> findByConversationIdOrderByCreatedAtAscIdAsc(Long conversationId, Pageable pageable);

    void deleteAllBySenderId(Long senderId);
}
>>>>>>> origin/main
