package mygrant.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

class EmailPropertiesTests {

    @Test
    void backoffFollowsTheListAndRepeatsTheLastStep() {
        EmailProperties properties = EmailTestData.properties();

        assertThat(properties.backoffAfter(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.backoffAfter(2)).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.backoffAfter(3)).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.backoffAfter(7)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void linksIgnoreATrailingSlashOnTheBaseUrl() {
        EmailProperties properties = new EmailProperties("https://mygrant.example/", false, 4,
                List.of(Duration.ofMinutes(1)), Duration.ofHours(24), Duration.ofMinutes(10), 50, ZoneId.of("UTC"), Duration.ofHours(48));

        assertThat(properties.notificationUrl(42L)).isEqualTo("https://mygrant.example/mygrant-prototype.html#notification=42");
        assertThat(properties.settingsUrl()).isEqualTo("https://mygrant.example/mygrant-prototype.html#view=profile");
        assertThat(properties.unsubscribeUrl("7.abc")).isEqualTo("https://mygrant.example/api/v1/email/unsubscribe?token=7.abc");
    }

    @Test
    void rejectsSettingsThatWouldBreakRetries() {
        assertThatThrownBy(() -> new EmailProperties("http://localhost:8080", false, 0,
                List.of(Duration.ofMinutes(1)), Duration.ofHours(24), Duration.ofMinutes(10), 50, ZoneId.of("UTC"), Duration.ofHours(48)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmailProperties("http://localhost:8080", false, 4,
                List.of(), Duration.ofHours(24), Duration.ofMinutes(10), 50, ZoneId.of("UTC"), Duration.ofHours(48)))
                .isInstanceOf(IllegalArgumentException.class);
        // A live send must never look stuck, or recovery would start a second one.
        assertThatThrownBy(() -> new EmailProperties("http://localhost:8080", false, 4,
                List.of(Duration.ofMinutes(1)), Duration.ofHours(24), Duration.ofSeconds(20), 50, ZoneId.of("UTC"), Duration.ofHours(48)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
