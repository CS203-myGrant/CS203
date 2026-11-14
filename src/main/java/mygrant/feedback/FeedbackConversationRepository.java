package mygrant.feedback;

import java.util.Collection;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedbackConversationRepository extends JpaRepository<FeedbackConversation, Long> {
    Optional<FeedbackConversation> findFirstByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status);
    Page<FeedbackConversation> findAllByOrderByUpdatedAtDesc(Pageable pageable);

    Page<FeedbackConversation> findAllByUserIdInOrderByUpdatedAtDesc(Collection<Long> userIds,
            Pageable pageable);

    Optional<FeedbackConversation> findByIdAndUserIdIn(Long id, Collection<Long> userIds);

    void deleteAllByUserId(Long userId);
}
