package mygrant.email;

import static mygrant.email.EmailTestData.critical;
import static mygrant.email.EmailTestData.informational;
import static mygrant.email.EmailTestData.unassessed;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import mygrant.impact.ImpactUrgency;

class AlertBucketTests {

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

    @Test
    void highImpactUsesTheCriticalSetting() {
        assertThat(AlertBucket.of(ImpactUrgency.HIGH)).contains(AlertBucket.CRITICAL);
        assertThat(AlertBucket.of(critical(1L, 7L, NOW))).isEqualTo(AlertBucket.CRITICAL);
    }

    @Test
    void mediumLowAndUnassessedUseTheOtherSetting() {
        assertThat(AlertBucket.of(ImpactUrgency.MEDIUM)).contains(AlertBucket.OTHER);
        assertThat(AlertBucket.of(ImpactUrgency.LOW)).contains(AlertBucket.OTHER);
        assertThat(AlertBucket.of((ImpactUrgency) null)).contains(AlertBucket.OTHER);
        assertThat(AlertBucket.of(informational(1L, 7L, NOW))).isEqualTo(AlertBucket.OTHER);
    }

    @Test
    void notApplicableIsNeverEmailed() {
        assertThat(AlertBucket.of(ImpactUrgency.NOT_APPLICABLE)).isEmpty();
    }

    @Test
    void eachBucketReadsItsOwnSetting() {
        EmailPreference preference = new EmailPreference(7L, DeliveryMode.OFF, DeliveryMode.IMMEDIATE);

        assertThat(AlertBucket.CRITICAL.modeIn(preference)).isEqualTo(DeliveryMode.OFF);
        assertThat(AlertBucket.OTHER.modeIn(preference)).isEqualTo(DeliveryMode.IMMEDIATE);
    }

    @Test
    void theUsersSettingDecidesExceptThatUnassessedChangesOnlyEverJoinTheDigest() {
        EmailPreference everything = new EmailPreference(7L, DeliveryMode.IMMEDIATE, DeliveryMode.IMMEDIATE);
        assertThat(AlertBucket.modeFor(critical(1L, 7L, NOW), everything)).isEqualTo(DeliveryMode.IMMEDIATE);
        assertThat(AlertBucket.modeFor(informational(2L, 7L, NOW), everything)).isEqualTo(DeliveryMode.IMMEDIATE);
        assertThat(AlertBucket.modeFor(unassessed(3L, 7L, NOW), everything)).isEqualTo(DeliveryMode.DIGEST);

        EmailPreference defaults = EmailPreference.defaults(7L);
        assertThat(AlertBucket.modeFor(critical(1L, 7L, NOW), defaults)).isEqualTo(DeliveryMode.IMMEDIATE);
        assertThat(AlertBucket.modeFor(informational(2L, 7L, NOW), defaults)).isEqualTo(DeliveryMode.DIGEST);

        EmailPreference off = new EmailPreference(7L, DeliveryMode.OFF, DeliveryMode.OFF);
        assertThat(AlertBucket.modeFor(unassessed(3L, 7L, NOW), off)).isEqualTo(DeliveryMode.OFF);
    }
}
