package mygrant.email;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mygrant.common.ProfileValidationException;
import mygrant.email.dto.EmailPreferencesRequest;
import mygrant.email.dto.EmailPreferencesResponse;
import mygrant.user.User;
import mygrant.user.UserRepository;

/** Reads and changes a user's alert email modes, including through unsubscribe links. */
@Service
public class EmailPreferenceService {

    private final EmailPreferenceRepository preferenceRepository;
    private final UserRepository userRepository;
    private final EmailEligibility eligibility;

    public EmailPreferenceService(EmailPreferenceRepository preferenceRepository,
            UserRepository userRepository, EmailEligibility eligibility) {
        this.preferenceRepository = preferenceRepository;
        this.userRepository = userRepository;
        this.eligibility = eligibility;
    }

    @Transactional(readOnly = true)
    public EmailPreferencesResponse get(String email) {
        User user = currentUser(email);
        return response(user, preferenceFor(user.getId()));
    }

    @Transactional
    public EmailPreferencesResponse update(String email, EmailPreferencesRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        DeliveryMode critical = parse("criticalDelivery", request.criticalDelivery(), errors);
        DeliveryMode other = parse("otherDelivery", request.otherDelivery(), errors);
        if (!errors.isEmpty()) {
            throw new ProfileValidationException(errors);
        }

        User user = currentUser(email);
        EmailPreference preference = preferenceFor(user.getId());
        preference.update(critical, other);
        return response(user, preferenceRepository.save(preference));
    }

    /** The saved modes, or the defaults for a user who never changed them. */
    @Transactional(readOnly = true)
    public EmailPreference preferenceFor(Long userId) {
        return preferenceRepository.findById(userId).orElseGet(() -> EmailPreference.defaults(userId));
    }

    /**
     * Turns off both kinds of alert email. Repeating it changes nothing, and a closed
     * account is left alone so a link in an old email cannot recreate data for it.
     */
    @Transactional
    public void unsubscribe(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || !user.isActive() || user.getDeletedAt() != null) {
            return;
        }
        EmailPreference preference = preferenceFor(userId);
        if (preference.getCriticalDelivery() == DeliveryMode.OFF
                && preference.getOtherDelivery() == DeliveryMode.OFF) {
            return;
        }
        preference.update(DeliveryMode.OFF, DeliveryMode.OFF);
        preferenceRepository.save(preference);
    }

    private EmailPreferencesResponse response(User user, EmailPreference preference) {
        IneligibleReason reason = eligibility.check(user).orElse(null);
        return new EmailPreferencesResponse(preference.getCriticalDelivery(), preference.getOtherDelivery(),
                user.getEmail(), reason == null, reason);
    }

    private DeliveryMode parse(String field, String value, Map<String, String> errors) {
        if (value != null) {
            for (DeliveryMode mode : DeliveryMode.values()) {
                if (mode.name().equalsIgnoreCase(value.trim())) {
                    return mode;
                }
            }
        }
        errors.put(field, "Must be one of IMMEDIATE, DIGEST or OFF");
        return null;
    }

    private User currentUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists"));
    }
}
