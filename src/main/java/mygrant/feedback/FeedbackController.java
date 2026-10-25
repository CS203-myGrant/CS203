package mygrant.feedback;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

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
    public Page<FeedbackMessageResponse> messages(Authentication authentication,
            @PageableDefault(size = 20) Pageable pageable) {
        return feedbackService.applicantMessages(authentication.getName(), pageable);
    }

    @PostMapping("/messages")
    public FeedbackMessageResponse send(Authentication authentication,
            @Valid @RequestBody FeedbackMessageRequest request) {
        return feedbackService.sendAsApplicant(authentication.getName(), request.message());
    }

    @GetMapping("/admin/conversations")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public Page<FeedbackConversationResponse> conversations(Authentication authentication,
            @PageableDefault(size = 20) Pageable pageable) {
        return feedbackService.staffConversations(authentication.getName(), pageable);
    }

    @GetMapping("/admin/conversations/{id}/messages")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public Page<FeedbackMessageResponse> staffMessages(Authentication authentication,
            @PathVariable Long id, @PageableDefault(size = 20) Pageable pageable) {
        return feedbackService.staffMessages(authentication.getName(), id, pageable);
    }

    @PostMapping("/admin/conversations/{id}/messages")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public FeedbackMessageResponse staffSend(Authentication authentication, @PathVariable Long id,
            @Valid @RequestBody FeedbackMessageRequest request) {
        return feedbackService.sendAsStaff(authentication.getName(), id, request.message());
    }
}
