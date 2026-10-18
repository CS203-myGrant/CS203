package mygrant.feedback;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mygrant.feedback.dto.FeedbackConversationResponse;
import mygrant.feedback.dto.FeedbackMessageResponse;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

@Service
public class FeedbackService {
    private final FeedbackConversationRepository conversationRepository;
    private final FeedbackMessageRepository messageRepository;
    private final UserRepository userRepository;

    public FeedbackService(FeedbackConversationRepository conversationRepository,
            FeedbackMessageRepository messageRepository, UserRepository userRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public FeedbackConversationResponse getOrCreate(String email) {
        User user = user(email);
        FeedbackConversation conversation = conversationRepository
                .findFirstByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), "OPEN")
                .orElseGet(() -> conversationRepository.save(new FeedbackConversation(user.getId())));
        return response(conversation, user);
    }

    public List<FeedbackMessageResponse> applicantMessages(String email) {
        User user = user(email);
        FeedbackConversation conversation = ownedOpenConversation(user);
        return messageResponses(conversation);
    }

    @Transactional
    public FeedbackMessageResponse sendAsApplicant(String email, String body) {
        User user = user(email);
        FeedbackConversation conversation = conversationRepository
                .findFirstByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), "OPEN")
                .orElseGet(() -> conversationRepository.save(new FeedbackConversation(user.getId())));
        return saveMessage(conversation, user, "APPLICANT", body);
    }

    public List<FeedbackConversationResponse> staffConversations(String email) {
        requireStaff(user(email));
        Map<Long, User> users = userRepository.findAllById(conversationRepository.findAll().stream()
                        .map(FeedbackConversation::getUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return conversationRepository.findAllByOrderByUpdatedAtDesc().stream()
                .map(conversation -> response(conversation, users.get(conversation.getUserId())))
                .toList();
    }

    public List<FeedbackMessageResponse> staffMessages(String email, Long conversationId) {
        requireStaff(user(email));
        return messageResponses(conversation(conversationId));
    }

    @Transactional
    public FeedbackMessageResponse sendAsStaff(String email, Long conversationId, String body) {
        User staff = user(email);
        requireStaff(staff);
        return saveMessage(conversation(conversationId), staff, "STAFF", body);
    }

    private FeedbackMessageResponse saveMessage(FeedbackConversation conversation, User sender,
            String senderType, String body) {
        String trimmed = body.trim();
        FeedbackMessage saved = messageRepository.save(
                new FeedbackMessage(conversation.getId(), sender.getId(), senderType, trimmed));
        conversation.touch();
        conversationRepository.save(conversation);
        return FeedbackMessageResponse.from(saved, sender.getFullName());
    }

    private List<FeedbackMessageResponse> messageResponses(FeedbackConversation conversation) {
        List<FeedbackMessage> messages = messageRepository
                .findByConversationIdOrderByCreatedAtAscIdAsc(conversation.getId());
        Map<Long, User> senders = userRepository.findAllById(messages.stream()
                        .map(FeedbackMessage::getSenderId).distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return messages.stream().map(message -> {
            User sender = senders.get(message.getSenderId());
            return FeedbackMessageResponse.from(message,
                    sender == null ? "Unknown user" : sender.getFullName());
        }).toList();
    }

    private FeedbackConversation ownedOpenConversation(User user) {
        return conversationRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), "OPEN")
                .orElseThrow(() -> new FeedbackNotFoundException("No open feedback conversation"));
    }

    private FeedbackConversation conversation(Long id) {
        return conversationRepository.findById(id)
                .orElseThrow(() -> new FeedbackNotFoundException("Feedback conversation not found"));
    }

    private User user(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists"));
    }

    private void requireStaff(User user) {
        if (user.getRole() != UserRole.ADMIN && user.getRole() != UserRole.MANAGER) {
            throw new FeedbackAccessDeniedException();
        }
    }

    private FeedbackConversationResponse response(FeedbackConversation conversation, User user) {
        return new FeedbackConversationResponse(conversation.getId(), conversation.getUserId(),
                user.getFullName(), user.getEmail(), conversation.getStatus(),
                conversation.getCreatedAt(), conversation.getUpdatedAt());
    }
}
