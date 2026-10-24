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

import mygrant.notifications.dto.NotificationResponse;
import mygrant.user.UserRepository;

/** The logged-in user's notification inbox. */
@RestController
@RequestMapping("/api/v1/notifications")
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
    public SseEmitter stream(Authentication authentication) {
        Long userId = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists")).getId();
        return streamRegistry.connect(userId);
    }

    @GetMapping
    public Page<NotificationResponse> list(Authentication authentication,
            @PageableDefault(size = 20) Pageable pageable) {
        return notificationService.list(authentication.getName(), pageable);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(Authentication authentication) {
        return Map.of("unread", notificationService.unreadCount(authentication.getName()));
    }

    /** Opens one alert, e.g. from an email link; another user's id is the same 404 as a missing one. */
    @GetMapping("/{id}")
    public NotificationResponse get(Authentication authentication, @PathVariable Long id) {
        return notificationService.get(authentication.getName(), id);
    }

    @PostMapping("/{id}/read")
    public NotificationResponse markRead(Authentication authentication, @PathVariable Long id) {
        return notificationService.markRead(authentication.getName(), id);
    }
}
