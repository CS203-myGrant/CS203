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
    void theGraceFactorCannotBeBelowOneOrInfinite() {
        assertThatThrownBy(() -> new SourceFreshnessService(repository, 0.5, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceFreshnessService(repository, Double.POSITIVE_INFINITY, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PolicySource at(String name, PolicySourceType type, String url, Duration sinceSuccess) {
        PolicySource source = new PolicySource(name, type, url, 3600);
        if (sinceSuccess != null) source.recordSuccess(NOW.minus(sinceSuccess));
        return source;
    }

    /** One assessment is judged only by the sources whose host matches a URL it cites. */
    @Test
    void anAssessmentIsJudgedByTheSourcesItCites() {
        when(repository.findAllByEnabledTrueOrderByIdAsc()).thenReturn(List.of(
                at("register", PolicySourceType.FEDERAL_REGISTER,
                        "https://api.federalregister.gov/v1/documents.json?per_page=20", Duration.ofHours(1)),
                at("uscis news", PolicySourceType.RSS, "https://www.uscis.gov/news/rss-feed/59144", Duration.ofDays(5))));
        var snapshot = service.snapshot();

        // The API host and the document pages are the same provider; "www." is ignored.
        var register = snapshot.forUrls(List.of("https://federalregister.gov/documents/2026/11/01/2026-1/rule"));
        assertThat(register.stale()).isFalse();
        assertThat(register.sources()).extracting(SourceFreshnessService.SourceFreshness::name).containsExactly("register");

        var uscis = snapshot.forUrls(List.of("https://www.uscis.gov/newsroom/alerts/item", "not a url"));
        assertThat(uscis.stale()).isTrue();
        assertThat(uscis.staleSince()).isEqualTo(NOW.minus(Duration.ofDays(5)));

        // A page citing no known source has nothing to warn about; the whole feed still does.
        assertThat(snapshot.forUrls(List.of("https://www.whitehouse.gov/proclamation")).stale()).isFalse();
        assertThat(snapshot.forUrls(List.of()).sources()).isEmpty();
        assertThat(snapshot.all().stale()).isTrue();
    }

    @Test
    void onlyHttpUrlsHaveAHost() {
        assertThat(SourceFreshnessService.host("HTTPS://WWW.Example.org/path")).isEqualTo("example.org");
        assertThat(SourceFreshnessService.host("https://api.federalregister.gov/v1")).isEqualTo("federalregister.gov");
        assertThat(SourceFreshnessService.host("ftp://example.org/file")).isNull();
        assertThat(SourceFreshnessService.host("mailto:someone@example.org")).isNull();
        assertThat(SourceFreshnessService.host("not a url")).isNull();
        assertThat(SourceFreshnessService.host(null)).isNull();
    }
}
