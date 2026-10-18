package mygrant.notifications;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import mygrant.notifications.dto.NotificationResponse;

/** In-memory SSE connections, partitioned by authenticated user id. */
@Component
public class NotificationStreamRegistry {

    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter connect(Long userId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.compute(userId, (ignored, userEmitters) -> {
            CopyOnWriteArrayList<SseEmitter> connections = userEmitters == null
                    ? new CopyOnWriteArrayList<>() : userEmitters;
            connections.add(emitter);
            return connections;
        });
        Runnable remove = () -> remove(userId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(ignored -> remove.run());
        return emitter;
    }

    /** A broken tab is removed without affecting other tabs or notification creation. */
    public void publish(Long userId, NotificationResponse notification) {
        List<SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters == null) return;
        for (SseEmitter emitter : userEmitters) {
            try {
                emitter.send(SseEmitter.event().name("notification").id(notification.id().toString())
                        .data(notification));
            } catch (IOException | IllegalStateException exception) {
                remove(userId, emitter);
                try { emitter.completeWithError(exception); } catch (IllegalStateException ignored) { }
            }
        }
    }

    /** Closes every live notification stream for an account being deleted. */
    public void closeAll(Long userId) {
        List<SseEmitter> userEmitters = emitters.remove(userId);
        if (userEmitters != null) {
            userEmitters.forEach(SseEmitter::complete);
        }
    }

    private void remove(Long userId, SseEmitter emitter) {
        emitters.computeIfPresent(userId, (ignored, userEmitters) -> {
            userEmitters.remove(emitter);
            return userEmitters.isEmpty() ? null : userEmitters;
        });
    }
}
