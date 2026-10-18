package mygrant.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Verifies how a stored notification reports the profile fields it refers to. */
class NotificationTests {

    @Test
    void reportsNoFieldsWhenNoneWereRecorded() {
        Notification notification = new Notification(7L, 1, 3L, "msg", List.of());

        assertThat(notification.isActionRequired()).isFalse();
        assertThat(notification.getAffectedFields()).isEmpty();
    }

    @Test
    void skipsStoredFieldNamesThatNoLongerExist() {
        Notification notification = new Notification(7L, 1, 3L, "msg",
                List.of(ProfileField.PROGRAM_END_DATE));
        // A row written before a later release renamed or removed a field.
        ReflectionTestUtils.setField(notification, "affectedFields", "PROGRAM_END_DATE,VISA_EXPIRY");

        assertThat(notification.getAffectedFields()).containsExactly(ProfileField.PROGRAM_END_DATE);
    }
}
