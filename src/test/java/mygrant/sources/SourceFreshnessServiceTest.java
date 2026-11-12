package mygrant.sources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

/** CS203-175: a source is stale past its poll interval times the grace factor, or if it never synced. */
class SourceFreshnessServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");
    private final PolicySourceRepository repository = mock(PolicySourceRepository.class);
    private final SourceFreshnessService service =
            new SourceFreshnessService(repository, 3, Clock.fixed(NOW, ZoneOffset.UTC));

    private static PolicySource source(String name, PolicySourceType type, int pollSeconds, Duration sinceSuccess) {
        PolicySource source = new PolicySource(name, type, "https://example.org/" + name, pollSeconds);
        if (sinceSuccess != null) source.recordSuccess(NOW.minus(sinceSuccess));
        return source;
    }

    @Test
    void freshWithinThreePollIntervalsAndStaleAfter() {
        when(repository.findAllByEnabledTrueOrderByIdAsc()).thenReturn(List.of(
                source("fresh", PolicySourceType.FEDERAL_REGISTER, 3600, Duration.ofHours(3)),
                source("stale", PolicySourceType.NEWS, 3600, Duration.ofHours(3).plusMinutes(1))));

        var freshness = service.current();

        assertThat(freshness.stale()).isTrue();
        assertThat(freshness.sources()).extracting(SourceFreshnessService.SourceFreshness::name,
                SourceFreshnessService.SourceFreshness::stale).containsExactly(
                org.assertj.core.groups.Tuple.tuple("fresh", false), org.assertj.core.groups.Tuple.tuple("stale", true));
        assertThat(freshness.staleSince()).isEqualTo(NOW.minus(Duration.ofHours(3).plusMinutes(1)));
    }

    @Test
    void aSourceThatNeverSyncedIsStaleWithNoDate() {
        when(repository.findAllByEnabledTrueOrderByIdAsc()).thenReturn(List.of(
                source("new", PolicySourceType.RSS, 3600, null),
                source("old", PolicySourceType.FEDERAL_REGISTER, 60, Duration.ofDays(2))));

        var freshness = service.current();

        assertThat(freshness.stale()).isTrue();
        assertThat(freshness.staleSince()).isNull();
    }

    @Test
    void sourcesThatProduceNoPoliciesAreLeftOut() {
        when(repository.findAllByEnabledTrueOrderByIdAsc()).thenReturn(List.of(
                source("bulletin", PolicySourceType.VISA_BULLETIN, 60, null),
                source("uscis", PolicySourceType.USCIS, 60, null),
                source("register", PolicySourceType.FEDERAL_REGISTER, 3600, Duration.ofMinutes(5))));

        var freshness = service.current();

        assertThat(freshness.stale()).isFalse();
        assertThat(freshness.staleSince()).isNull();
        assertThat(freshness.sources()).extracting(SourceFreshnessService.SourceFreshness::name)
                .containsExactly("register");
    }

    @Test
    void theGraceFactorCannotBeBelowOne() {
        assertThatThrownBy(() -> new SourceFreshnessService(repository, 0.5, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
