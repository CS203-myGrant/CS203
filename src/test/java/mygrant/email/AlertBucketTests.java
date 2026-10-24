package mygrant.email;

import static mygrant.email.EmailTestData.critical;
import static mygrant.email.EmailTestData.informational;
import static mygrant.email.EmailTestData.withSeverity;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class AlertBucketTests {

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

    @Test
    void actionRequiredAlertsAreCritical() {
        assertThat(AlertBucket.of(critical(1L, 7L, NOW))).isEqualTo(AlertBucket.CRITICAL);
    }

    @Test
    void highImpactAlertsAreCriticalEvenWithoutAnAction() {
        assertThat(AlertBucket.of(withSeverity(informational(1L, 7L, NOW), "HIGH"))).isEqualTo(AlertBucket.CRITICAL);
    }

    @Test
    void everythingElseIsOther() {
        assertThat(AlertBucket.of(informational(1L, 7L, NOW))).isEqualTo(AlertBucket.OTHER);
        assertThat(AlertBucket.of(withSeverity(informational(2L, 7L, NOW), "MEDIUM"))).isEqualTo(AlertBucket.OTHER);
        assertThat(AlertBucket.of(withSeverity(informational(3L, 7L, NOW), "UNAVAILABLE"))).isEqualTo(AlertBucket.OTHER);
    }

    @Test
    void eachBucketReadsItsOwnSetting() {
        EmailPreference preference = new EmailPreference(7L, DeliveryMode.OFF, DeliveryMode.IMMEDIATE);

        assertThat(AlertBucket.CRITICAL.modeIn(preference)).isEqualTo(DeliveryMode.OFF);
        assertThat(AlertBucket.OTHER.modeIn(preference)).isEqualTo(DeliveryMode.IMMEDIATE);
    }
}
