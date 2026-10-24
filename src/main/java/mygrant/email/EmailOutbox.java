package mygrant.email;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mygrant.notifications.NotificationRepository;

/** Adds emails to the outbox together with the alerts they carry, in one transaction. */
@Service
public class EmailOutbox {

    private final EmailDeliveryRepository deliveryRepository;
    private final NotificationRepository notificationRepository;

    public EmailOutbox(EmailDeliveryRepository deliveryRepository, NotificationRepository notificationRepository) {
        this.deliveryRepository = deliveryRepository;
        this.notificationRepository = notificationRepository;
    }

    /**
     * Queues an immediate email for one alert. Empty when another run already put the alert
     * in an email; the claim is a conditional update, so only one run can win it.
     */
    @Transactional
    public Optional<UUID> queueImmediate(Long userId, Long notificationId, Instant now) {
        UUID id = UUID.randomUUID();
        deliveryRepository.insertImmediate(id, userId, now);
        if (notificationRepository.claimForDelivery(id, List.of(notificationId)) == 0) {
            deliveryRepository.deleteById(id);
            return Optional.empty();
        }
        return Optional.of(id);
    }
}
