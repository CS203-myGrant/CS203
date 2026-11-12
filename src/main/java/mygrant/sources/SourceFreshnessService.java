package mygrant.sources;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * CS203-175: whether the sources that feed users' impact assessments are still updating. A source
 * is stale when it has never synced, or when its last successful sync is older than its poll
 * interval times {@code mygrant.sources.stale-grace-factor}. The grace factor allows for a missed
 * run or two (the scheduler, a slow provider, a redeploy) before users are warned.
 */
@Service
public class SourceFreshnessService {

    /** The sources that produce policy documents, and so impact assessments. */
    static final Set<PolicySourceType> POLICY_SOURCES =
            Set.of(PolicySourceType.FEDERAL_REGISTER, PolicySourceType.NEWS, PolicySourceType.RSS);

    @Schema(description = "One enabled policy source and whether it is still updating")
    public record SourceFreshness(String name, PolicySourceType type,
            @Schema(nullable = true, description = "When it last synced successfully; null if it never has")
            Instant lastSuccessAt,
            int pollIntervalSeconds,
            @Schema(description = "True when it never synced, or not within poll interval × grace factor")
            boolean stale) {}

    @Schema(description = "Freshness of the sources behind these assessments")
    public record Freshness(
            @Schema(description = "True when any source is stale; show an out-of-date warning")
            boolean stale,
            @Schema(nullable = true, description = "The oldest last successful sync among stale sources; "
                    + "null when none is stale or a stale source never synced")
            Instant staleSince,
            List<SourceFreshness> sources) {
        public static final Freshness UNKNOWN = new Freshness(false, null, List.of());
    }

    private final PolicySourceRepository sources;
    private final double graceFactor;
    private final Clock clock;

    @Autowired
    public SourceFreshnessService(PolicySourceRepository sources,
            @Value("${mygrant.sources.stale-grace-factor:2}") double graceFactor) {
        this(sources, graceFactor, Clock.systemUTC());
    }

    SourceFreshnessService(PolicySourceRepository sources, double graceFactor, Clock clock) {
        if (!Double.isFinite(graceFactor) || graceFactor < 1) {
            throw new IllegalArgumentException("mygrant.sources.stale-grace-factor must be at least 1");
        }
        this.sources = sources;
        this.graceFactor = graceFactor;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Freshness current() {
        return snapshot().all();
    }

    /** Load once per response, including for empty feeds. No per-assessment database queries. */
    @Transactional(readOnly = true)
    public Snapshot snapshot() {
        return new Snapshot(sources.findAllByEnabledTrueOrderByIdAsc().stream()
                .filter(source -> POLICY_SOURCES.contains(source.getType())).toList(), clock.instant());
    }

    /** The enabled policy sources, read once, judged against one moment. */
    public final class Snapshot {
        private final List<PolicySource> sources;
        private final Instant now;

        private Snapshot(List<PolicySource> sources, Instant now) {
            this.sources = sources;
            this.now = now;
        }

        /** Every enabled policy source: for a whole feed, even an empty one. */
        public Freshness all() {
            return summarize(sources, now);
        }

        /**
         * The sources behind one assessment. Policies record their URL but not the source that
         * found them, so a source matches when its URL has the same host as a cited URL.
         */
        public Freshness forUrls(List<String> urls) {
            Set<String> hosts = urls.stream().map(SourceFreshnessService::host)
                    .filter(Objects::nonNull).collect(Collectors.toSet());
            return summarize(sources.stream().filter(source -> hosts.contains(host(source.getUrl())))
                    .toList(), now);
        }
    }

    /** The provider host of an http(s) URL, ignoring "www."; null for anything else. */
    static String host(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (uri.getHost() == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return null;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
            // The Federal Register API and its document pages are the same provider.
            return host.equals("api.federalregister.gov") ? "federalregister.gov" : host;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private Freshness summarize(List<PolicySource> sources, Instant now) {
        List<SourceFreshness> relevant = sources.stream()
                .map(source -> new SourceFreshness(source.getName(), source.getType(), source.getLastSuccessAt(),
                        source.getPollIntervalSeconds(), stale(source, now)))
                .toList();
        List<SourceFreshness> stale = relevant.stream().filter(SourceFreshness::stale).toList();
        Instant staleSince = stale.isEmpty() || stale.stream().anyMatch(source -> source.lastSuccessAt() == null)
                ? null
                : stale.stream().map(SourceFreshness::lastSuccessAt).filter(Objects::nonNull)
                        .min(Comparator.naturalOrder()).orElse(null);
        return new Freshness(!stale.isEmpty(), staleSince, relevant);
    }

    private boolean stale(PolicySource source, Instant now) {
        if (source.getLastSuccessAt() == null) {
            return true;
        }
        Duration allowed = Duration.ofMillis((long) (source.getPollIntervalSeconds() * 1000L * graceFactor));
        return Duration.between(source.getLastSuccessAt(), now).compareTo(allowed) > 0;
    }
}
