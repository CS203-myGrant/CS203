package mygrant.email;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


/** Adds emails to the outbox together with the items they carry, in one transaction. */
@Service
public class EmailOutbox {

    private final EmailDeliveryRepository deliveryRepository;
    private final ImpactEmailAlertRepository alertRepository;

    public EmailOutbox(EmailDeliveryRepository deliveryRepository, ImpactEmailAlertRepository alertRepository) {
        this.deliveryRepository = deliveryRepository;
        this.alertRepository = alertRepository;
    }

    /**
     * Queues an immediate email for one item. Empty when another run already put the item in an
     * email; the claim is a conditional update, so only one run can win it.
     */
    @Transactional
    public Optional<UUID> queueImmediate(Long userId, Long alertId, Instant now) {
        UUID id = UUID.randomUUID();
        deliveryRepository.insertImmediate(id, userId, now);
        if (alertRepository.claimForDelivery(id, List.of(alertId)) == 0) {
            deliveryRepository.deleteById(id);
            return Optional.empty();
        }
        return Optional.of(id);
    }

    /**
     * Queues the user's digest for that date with whichever of the items no other email has
     * claimed. Empty when the user already has a digest for the date or none of the items were
     * still free.
     */
    @Transactional
    public Optional<UUID> queueDigest(Long userId, LocalDate digestDate, Collection<Long> alertIds,
            Instant now) {
        UUID id = UUID.randomUUID();
        if (deliveryRepository.insertDigest(id, userId, digestDate, now) == 0) {
            return Optional.empty();
        }
        if (alertRepository.claimForDelivery(id, alertIds) == 0) {
            deliveryRepository.deleteById(id);
            return Optional.empty();
        }
        return Optional.of(id);
    }
}
