package mygrant.feedback;

import org.springdoc.core.annotations.ParameterObject;
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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import mygrant.feedback.dto.FeedbackConversationResponse;
import mygrant.feedback.dto.FeedbackMessageRequest;
import mygrant.feedback.dto.FeedbackMessageResponse;

@RestController
@RequestMapping("/api/v1/feedback")
@Tag(name = "Support chat")
public class FeedbackController {
    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @PostMapping("/conversation")
    @Operation(summary = "Open the signed-in user's support conversation",
            description = "Returns the open conversation, creating one if there is none.")
    public FeedbackConversationResponse conversation(Authentication authentication) {
        return feedbackService.getOrCreate(authentication.getName());
    }

    @GetMapping("/messages")
    @Operation(summary = "List messages in the signed-in user's open conversation", description = "Oldest first.")
    @ApiResponse(responseCode = "200", description = "A page of messages")
    @ApiResponse(responseCode = "400", description = "Unknown sort property")
    @ApiResponse(responseCode = "404", description = "The user has no open conversation")
    public Page<FeedbackMessageResponse> messages(Authentication authentication,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return feedbackService.applicantMessages(authentication.getName(), pageable);
    }

    @PostMapping("/messages")
    @Operation(summary = "Send a message to the support team",
            description = "Opens a conversation first if the user has none.")
    @ApiResponse(responseCode = "200", description = "The saved message")
    @ApiResponse(responseCode = "400", description = "The message is blank or longer than 2000 characters")
    public FeedbackMessageResponse send(Authentication authentication,
            @Valid @RequestBody FeedbackMessageRequest request) {
        return feedbackService.sendAsApplicant(authentication.getName(), request.message());
    }

    @GetMapping("/admin/conversations")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "List every support conversation", description = "Most recently active first.")
    @ApiResponse(responseCode = "200", description = "A page of conversations")
    @ApiResponse(responseCode = "400", description = "Unknown sort property")
    public Page<FeedbackConversationResponse> conversations(Authentication authentication,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return feedbackService.staffConversations(authentication.getName(), pageable);
    }

    @GetMapping("/admin/conversations/{id}/messages")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "List messages in a support conversation", description = "Oldest first.")
    @ApiResponse(responseCode = "200", description = "A page of messages")
    @ApiResponse(responseCode = "400", description = "The id is not a number, or unknown sort property")
    @ApiResponse(responseCode = "404", description = "No conversation has this id")
    public Page<FeedbackMessageResponse> staffMessages(Authentication authentication,
            @PathVariable Long id, @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return feedbackService.staffMessages(authentication.getName(), id, pageable);
    }

    @PostMapping("/admin/conversations/{id}/messages")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Reply in a support conversation as staff")
    @ApiResponse(responseCode = "200", description = "The saved reply")
    @ApiResponse(responseCode = "400", description = "The id is not a number, or the message is blank or longer "
            + "than 2000 characters")
    @ApiResponse(responseCode = "404", description = "No conversation has this id")
    public FeedbackMessageResponse staffSend(Authentication authentication, @PathVariable Long id,
            @Valid @RequestBody FeedbackMessageRequest request) {
        return feedbackService.sendAsStaff(authentication.getName(), id, request.message());
    }
}
