package mygrant.notifications;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Holds each user's open server-sent event streams and pushes new notifications to them. */
@Component
public class NotificationStream {

    // The page reconnects when a stream ends, so the timeout only bounds how long a
    // stream abandoned without a clean close can linger before it is dropped.
    private static final long TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();

    // A user may have the app open in several tabs, and every tab should see the popup.
    private final Map<String, Set<SseEmitter>> emittersByEmail = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String email) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        Set<SseEmitter> emitters = emittersByEmail.computeIfAbsent(email, key -> new CopyOnWriteArraySet<>());
        emitters.add(emitter);
        Runnable remove = () -> emitters.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        try {
            // Flushes the response headers so the page knows the stream is open.
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /**
     * Pushes a notification once the transaction that stored it commits, so a page that
     * reloads its inbox on receipt is guaranteed to find the new row.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        for (SseEmitter emitter : emittersByEmail.getOrDefault(event.recipientEmail(), Set.of())) {
            try {
                emitter.send(SseEmitter.event().name("notification").data(event.notification()));
            } catch (IOException | IllegalStateException e) {
                // The tab was closed; dropping the stream here stops further sends to it.
                emitter.completeWithError(e);
            }
        }
    }
}
