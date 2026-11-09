package mygrant.notifications;

import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.springdoc.core.annotations.ParameterObject;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import mygrant.notifications.dto.NotificationResponse;
import mygrant.user.UserRepository;

/** The logged-in user's notification inbox. */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationStreamRegistry streamRegistry;
    private final UserRepository userRepository;

    public NotificationController(NotificationService notificationService,
            NotificationStreamRegistry streamRegistry, UserRepository userRepository) {
        this.notificationService = notificationService;
        this.streamRegistry = streamRegistry;
        this.userRepository = userRepository;
    }

    /** The regular bearer-token security filter authenticates this request; the JWT is never in the URL. */
    @GetMapping(path = "/stream", produces = "text/event-stream")
    @Operation(summary = "Receive new alerts as they are created",
            description = "A server-sent event stream. Each new alert arrives as a `notification` event whose "
                    + "data is the alert. The stream sends a `connected` comment first and stays open.")
    @ApiResponse(responseCode = "200", description = "An open event stream", content = @Content(
            mediaType = "text/event-stream", schema = @Schema(implementation = NotificationResponse.class)))
    public SseEmitter stream(Authentication authentication) {
        Long userId = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists")).getId();
        return streamRegistry.connect(userId);
    }

    @GetMapping
    @Operation(summary = "List the signed-in user's alerts", description = "Newest first.")
    @ApiResponse(responseCode = "200", description = "A page of alerts")
    @ApiResponse(responseCode = "400", description = "Unknown sort property")
    public Page<NotificationResponse> list(Authentication authentication,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return notificationService.list(authentication.getName(), pageable);
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Count the signed-in user's unread alerts")
    @ApiResponse(responseCode = "200", description = "For example `{\"unread\": 3}`")
    public Map<String, Long> unreadCount(Authentication authentication) {
        return Map.of("unread", notificationService.unreadCount(authentication.getName()));
    }

    /** Opens one alert, e.g. from an email link; another user's id is the same 404 as a missing one. */
    @GetMapping("/{id}")
    @Operation(summary = "Get one of the signed-in user's alerts")
    @ApiResponse(responseCode = "200", description = "The alert")
    @ApiResponse(responseCode = "400", description = "The id is not a number")
    @ApiResponse(responseCode = "404", description = "No alert with this id belongs to the user")
    public NotificationResponse get(Authentication authentication, @PathVariable Long id) {
        return notificationService.get(authentication.getName(), id);
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Mark one of the signed-in user's alerts as read")
    @ApiResponse(responseCode = "200", description = "The alert, now read")
    @ApiResponse(responseCode = "400", description = "The id is not a number")
    @ApiResponse(responseCode = "404", description = "No alert with this id belongs to the user")
    public NotificationResponse markRead(Authentication authentication, @PathVariable Long id) {
        return notificationService.markRead(authentication.getName(), id);
    }
}
