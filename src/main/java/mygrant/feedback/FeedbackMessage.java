package mygrant.feedback;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "feedback_messages")
public class FeedbackMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    @Column(name = "sender_type", nullable = false, length = 20)
    private String senderType;

    @Column(nullable = false, length = 2000)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected FeedbackMessage() {}

    public FeedbackMessage(Long conversationId, Long senderId, String senderType, String body) {
        this.conversationId = conversationId;
        this.senderId = senderId;
        this.senderType = senderType;
        this.body = body;
    }

    public Long getId() { return id; }
    public Long getConversationId() { return conversationId; }
    public Long getSenderId() { return senderId; }
    public String getSenderType() { return senderType; }
    public String getBody() { return body; }
    public Instant getCreatedAt() { return createdAt; }
}
