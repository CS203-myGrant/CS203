package mygrant.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;
import mygrant.rules.PolicyRuleStore.HistoryRow;
import mygrant.rules.RuleTimelineResponse.Step;

/** How stored history becomes timeline steps: previous values in order, and the derived TOOK_EFFECT step. */
class RuleTimelineStepsTest {

    private static final LocalDate SEP_15 = LocalDate.of(2026, 9, 15);
    private static final LocalDate NOV_1 = LocalDate.of(2026, 11, 1);
    private static final RuleState PROPOSED = new RuleState(PolicyStatus.PROPOSED, null, false);
    private static final RuleState FINAL = new RuleState(PolicyStatus.FINAL, SEP_15, false);

    @Test
    void previousValuesComeFromTheStepBeforeNotFromWhatWasStored() {
        // The proposal arrived after the final rule, so it was stored against the final state.
        List<HistoryRow> rows = List.of(
                row(1, PROPOSED, FINAL, false, "2025-16554", SourceType.PRIMARY, LocalDate.of(2025, 8, 28)),
                row(2, FINAL, null, true, "2026-14439", SourceType.PRIMARY, LocalDate.of(2026, 7, 17)));

        List<Step> steps = RuleTimelineService.steps(rows, FINAL, LocalDate.of(2026, 9, 1));

        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).previousPrimaryStage()).isNull();
        assertThat(steps.get(0).changes()).containsExactly(RuleChange.STAGE);
        assertThat(steps.get(1).previousPrimaryStage()).isEqualTo(PolicyStatus.PROPOSED);
        assertThat(steps.get(1).previousEffectiveDate()).isNull();
        assertThat(steps.get(1).changes()).containsExactly(RuleChange.STAGE, RuleChange.EFFECTIVE_DATE);
    }

    @Test
    void aFinalRuleTakesEffectOnTheReadersDateAfterTheStepThatSetTheDate() {
        RuleState moved = new RuleState(PolicyStatus.FINAL, NOV_1, false);
        RuleState disputed = new RuleState(PolicyStatus.FINAL, NOV_1, true);
        List<HistoryRow> rows = List.of(
                row(1, PROPOSED, null, true, "2025-16554", SourceType.PRIMARY, LocalDate.of(2025, 8, 28)),
                row(2, FINAL, PROPOSED, true, "2026-14439", SourceType.PRIMARY, LocalDate.of(2026, 7, 17)),
                row(3, moved, FINAL, true, "2026-99950", SourceType.PRIMARY, LocalDate.of(2026, 9, 1)),
                row(4, disputed, moved, true, "rss:1", SourceType.SECONDARY, LocalDate.of(2026, 11, 20)));

        assertThat(RuleTimelineService.steps(rows, disputed, NOV_1.minusDays(1))).hasSize(4)
                .noneMatch(Step::derived);

        List<Step> steps = RuleTimelineService.steps(rows, disputed, NOV_1);
        assertThat(steps).extracting(step -> step.changes().getFirst()).containsExactly(RuleChange.STAGE,
                RuleChange.STAGE, RuleChange.EFFECTIVE_DATE, RuleChange.TOOK_EFFECT, RuleChange.DISPUTED);
        Step tookEffect = steps.get(3);
        assertThat(tookEffect.derived()).isTrue();
        assertThat(tookEffect.recordedAt()).isNull();
        assertThat(tookEffect.observedOn()).isEqualTo(NOV_1);
        assertThat(tookEffect.primaryStage()).isEqualTo(PolicyStatus.FINAL);
        assertThat(tookEffect.disputed()).isFalse();
        assertThat(tookEffect.source().externalId()).isEqualTo("2026-99950");
        // News after the date leaves the primary stage alone.
        assertThat(steps.get(4).primaryStage()).isEqualTo(PolicyStatus.FINAL);
        assertThat(steps.get(4).disputed()).isTrue();
    }

    @Test
    void aDateAnnouncedAfterItPassedTakesEffectAfterTheAnnouncement() {
        RuleState retroactive = new RuleState(PolicyStatus.FINAL, SEP_15, false);
        List<HistoryRow> rows = List.of(
                row(1, PROPOSED, null, true, "2025-16554", SourceType.PRIMARY, LocalDate.of(2025, 8, 28)),
                row(2, retroactive, PROPOSED, true, "2026-77777", SourceType.PRIMARY, LocalDate.of(2026, 10, 1)));

        List<Step> steps = RuleTimelineService.steps(rows, retroactive, LocalDate.of(2026, 10, 2));

        assertThat(steps).extracting(Step::derived).containsExactly(false, false, true);
    }

    private static HistoryRow row(long id, RuleState state, RuleState storedPrevious, boolean applied,
            String externalId, SourceType sourceType, LocalDate published) {
        return new HistoryRow(id, 7L, state, storedPrevious, applied, false,
                new RuleSource(id, externalId, sourceType, "https://example.gov/" + externalId, "Title", "Agency",
                        published),
                RecordedBy.INGESTION, Instant.parse("2026-11-06T00:00:00Z").plusSeconds(id));
    }
}
