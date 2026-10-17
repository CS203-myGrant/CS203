package mygrant.feedback.dto;

import java.time.Instant;

import mygrant.feedback.FeedbackMessage;

public record FeedbackMessageResponse(Long id, String senderType, String senderName,
        String message, Instant createdAt) {
    public static FeedbackMessageResponse from(FeedbackMessage message, String senderName) {
        return new FeedbackMessageResponse(message.getId(), message.getSenderType(), senderName,
                message.getBody(), message.getCreatedAt());
    }
}
