package mygrant.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import mygrant.user.User;
import mygrant.user.UserRepository;

class NotificationStreamTests {

    @Test
    void streamIsRegisteredAgainstTheAuthenticatedUsersId() {
        NotificationService service = org.mockito.Mockito.mock(NotificationService.class);
        NotificationStreamRegistry registry = org.mockito.Mockito.mock(NotificationStreamRegistry.class);
        UserRepository users = org.mockito.Mockito.mock(UserRepository.class);
        User owner = new User("Owner", "owner@example.com", "hash", "F-1", null, null);
        ReflectionTestUtils.setField(owner, "id", 41L);
        when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(owner));
        SseEmitter expected = new SseEmitter(0L);
        when(registry.connect(41L)).thenReturn(expected);
        NotificationController controller = new NotificationController(service, registry, users);

        SseEmitter actual = controller.stream(new UsernamePasswordAuthenticationToken(
                "owner@example.com", null));

        assertThat(actual).isSameAs(expected);
        verify(registry).connect(41L);
    }
}
