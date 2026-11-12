package mygrant.sources;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
            @Value("${mygrant.sources.stale-grace-factor:3}") double graceFactor) {
        this(sources, graceFactor, Clock.systemUTC());
    }

    SourceFreshnessService(PolicySourceRepository sources, double graceFactor, Clock clock) {
        if (graceFactor < 1) {
            throw new IllegalArgumentException("mygrant.sources.stale-grace-factor must be at least 1");
        }
        this.sources = sources;
        this.graceFactor = graceFactor;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Freshness current() {
        Instant now = clock.instant();
        List<SourceFreshness> relevant = sources.findAllByEnabledTrueOrderByIdAsc().stream()
                .filter(source -> POLICY_SOURCES.contains(source.getType()))
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
