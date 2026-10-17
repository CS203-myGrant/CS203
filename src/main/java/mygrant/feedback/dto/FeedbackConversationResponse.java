package mygrant.feedback.dto;

import java.time.Instant;

public record FeedbackConversationResponse(Long id, Long userId, String userName, String userEmail,
        String status, Instant createdAt, Instant updatedAt) {}
