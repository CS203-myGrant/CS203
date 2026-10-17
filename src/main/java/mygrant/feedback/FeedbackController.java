package mygrant.feedback;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import mygrant.feedback.dto.FeedbackConversationResponse;
import mygrant.feedback.dto.FeedbackMessageRequest;
import mygrant.feedback.dto.FeedbackMessageResponse;

@RestController
@RequestMapping("/api/v1/feedback")
public class FeedbackController {
    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @PostMapping("/conversation")
    public FeedbackConversationResponse conversation(Authentication authentication) {
        return feedbackService.getOrCreate(authentication.getName());
    }

    @GetMapping("/messages")
    public List<FeedbackMessageResponse> messages(Authentication authentication) {
        return feedbackService.applicantMessages(authentication.getName());
    }

    @PostMapping("/messages")
    public FeedbackMessageResponse send(Authentication authentication,
            @Valid @RequestBody FeedbackMessageRequest request) {
        return feedbackService.sendAsApplicant(authentication.getName(), request.message());
    }

    @GetMapping("/admin/conversations")
    public List<FeedbackConversationResponse> conversations(Authentication authentication) {
        return feedbackService.staffConversations(authentication.getName());
    }

    @GetMapping("/admin/conversations/{id}/messages")
    public List<FeedbackMessageResponse> staffMessages(Authentication authentication,
            @PathVariable Long id) {
        return feedbackService.staffMessages(authentication.getName(), id);
    }

    @PostMapping("/admin/conversations/{id}/messages")
    public FeedbackMessageResponse staffSend(Authentication authentication, @PathVariable Long id,
            @Valid @RequestBody FeedbackMessageRequest request) {
        return feedbackService.sendAsStaff(authentication.getName(), id, request.message());
    }
}
