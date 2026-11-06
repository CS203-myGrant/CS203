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

/**
 * How stored history becomes timeline steps: previous values in order, the derived TOOK_EFFECT
 * step, and a separate final rule in the same rulemaking.
 */
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
        List<HistoryRow> rows = List.of(
                row(1, PROPOSED, null, true, "2025-16554", SourceType.PRIMARY, LocalDate.of(2025, 8, 28)),
                row(2, FINAL, PROPOSED, true, "2026-14439", SourceType.PRIMARY, LocalDate.of(2026, 7, 17)),
                row(3, moved, FINAL, true, true, "2026-99950", SourceType.PRIMARY, LocalDate.of(2026, 9, 1)),
                // A correction published after the date, which leaves it alone.
                row(4, new RuleState(PolicyStatus.FINAL, null, false), moved, true, true, "2026-99990",
                        SourceType.PRIMARY, LocalDate.of(2026, 11, 20)));

        assertThat(RuleTimelineService.steps(rows, moved, NOV_1.minusDays(1))).hasSize(4)
                .noneMatch(Step::derived);
        assertThat(RuleTimelineService.tookEffect(moved, NOV_1.minusDays(1))).isFalse();

        List<Step> steps = RuleTimelineService.steps(rows, moved, NOV_1);
        assertThat(RuleTimelineService.tookEffect(moved, NOV_1)).isTrue();
        assertThat(RuleTimelineService.effectiveDatePassedWhileDisputed(moved, NOV_1)).isFalse();
        assertThat(steps).extracting(Step::derived).containsExactly(false, false, false, true, false);
        Step tookEffect = steps.get(3);
        assertThat(tookEffect.changes()).containsExactly(RuleChange.TOOK_EFFECT);
        assertThat(tookEffect.recordedAt()).isNull();
        assertThat(tookEffect.observedOn()).isEqualTo(NOV_1);
        assertThat(tookEffect.primaryStage()).isEqualTo(PolicyStatus.FINAL);
        assertThat(tookEffect.disputed()).isFalse();
        assertThat(tookEffect.source().externalId()).isEqualTo("2026-99950");
    }

    @Test
    void aDisputedRuleNeverTakesEffectButSaysItsDatePassed() {
        // The D/S rule: final, effective 15 Sep 2026, postponed by a court on 14 Sep 2026.
        RuleState disputed = new RuleState(PolicyStatus.FINAL, SEP_15, true);
        List<HistoryRow> rows = List.of(
                row(1, PROPOSED, null, true, "2025-16554", SourceType.PRIMARY, LocalDate.of(2025, 8, 28)),
                row(2, FINAL, PROPOSED, true, "2026-14439", SourceType.PRIMARY, LocalDate.of(2026, 7, 17)),
                row(3, disputed, FINAL, true, "court-order", SourceType.SECONDARY, LocalDate.of(2026, 9, 14)));

        for (LocalDate asOf : List.of(SEP_15.minusDays(1), SEP_15, LocalDate.of(2026, 11, 6))) {
            List<Step> steps = RuleTimelineService.steps(rows, disputed, asOf);
            assertThat(steps).as("as of " + asOf).hasSize(3).noneMatch(Step::derived)
                    .noneMatch(step -> step.changes().contains(RuleChange.TOOK_EFFECT));
            assertThat(steps.getLast().changes()).containsExactly(RuleChange.DISPUTED);
            assertThat(steps.getLast().primaryStage()).isEqualTo(PolicyStatus.FINAL);
            assertThat(RuleTimelineService.tookEffect(disputed, asOf)).isFalse();
        }
        assertThat(RuleTimelineService.effectiveDatePassedWhileDisputed(disputed, SEP_15.minusDays(1))).isFalse();
        assertThat(RuleTimelineService.effectiveDatePassedWhileDisputed(disputed, SEP_15)).isTrue();
        assertThat(RuleTimelineService.effectiveDatePassedWhileDisputed(disputed, LocalDate.of(2026, 11, 6))).isTrue();
    }

    @Test
    void aSecondFinalRuleIsItsOwnStepAndLeavesTheRulesEffectiveDate() {
        LocalDate mar4 = LocalDate.of(2024, 3, 4);
        LocalDate jan17 = LocalDate.of(2025, 1, 17);
        RuleState first = new RuleState(PolicyStatus.FINAL, mar4, false);
        List<HistoryRow> rows = List.of(
                row(1, PROPOSED, null, true, "2023-23381", SourceType.PRIMARY, LocalDate.of(2023, 10, 23)),
                row(2, first, PROPOSED, true, "2024-01770", SourceType.PRIMARY, LocalDate.of(2024, 2, 2)),
                // Stored with its own date; the rule kept 4 Mar 2024.
                row(3, new RuleState(PolicyStatus.FINAL, jan17, false), first, true, "2024-29354",
                        SourceType.PRIMARY, LocalDate.of(2024, 12, 18)),
                row(4, new RuleState(PolicyStatus.FINAL, null, true), first, true, "rss:9",
                        SourceType.SECONDARY, LocalDate.of(2025, 2, 1)));

        List<Step> steps = RuleTimelineService.steps(rows, new RuleState(PolicyStatus.FINAL, mar4, true),
                LocalDate.of(2024, 3, 1));

        Step second = steps.get(2);
        assertThat(second.changes()).containsExactly(RuleChange.ADDITIONAL_FINAL_RULE);
        assertThat(second.primaryStage()).isEqualTo(PolicyStatus.FINAL);
        assertThat(second.previousPrimaryStage()).isEqualTo(PolicyStatus.FINAL);
        assertThat(second.effectiveDate()).isEqualTo(mar4);
        assertThat(second.previousEffectiveDate()).isEqualTo(mar4);
        assertThat(second.ownEffectiveDate()).isEqualTo(jan17);
        // The step after it reads the rule's date, not the second rule's.
        assertThat(steps.get(3).previousEffectiveDate()).isEqualTo(mar4);
        assertThat(steps.get(3).changes()).containsExactly(RuleChange.DISPUTED);
        assertThat(steps).filteredOn(step -> step != second).extracting(Step::ownEffectiveDate).containsOnlyNulls();
    }

    @Test
    void aSecondFinalRuleImportedFirstIsStillTheAdditionalOneInPublicationOrder() {
        LocalDate mar4 = LocalDate.of(2024, 3, 4);
        LocalDate jan17 = LocalDate.of(2025, 1, 17);
        RuleState second = new RuleState(PolicyStatus.FINAL, jan17, false);
        // 2024-29354 arrived first and moved the rule; 2024-01770 arrived late and was not applied.
        List<HistoryRow> rows = List.of(
                row(1, PROPOSED, null, true, "2023-23381", SourceType.PRIMARY, LocalDate.of(2023, 10, 23)),
                row(3, new RuleState(PolicyStatus.FINAL, mar4, false), second, false, "2024-01770",
                        SourceType.PRIMARY, LocalDate.of(2024, 2, 2)),
                row(2, second, PROPOSED, true, "2024-29354", SourceType.PRIMARY, LocalDate.of(2024, 12, 18)));

        List<Step> steps = RuleTimelineService.steps(rows, second, LocalDate.of(2024, 3, 1));

        assertThat(steps).extracting(Step::changes).containsExactly(List.of(RuleChange.STAGE),
                List.of(RuleChange.STAGE, RuleChange.EFFECTIVE_DATE), List.of(RuleChange.ADDITIONAL_FINAL_RULE));
        assertThat(steps.get(2).ownEffectiveDate()).isEqualTo(jan17);
    }

    @Test
    void aFinalDocumentAboutTheDateStillMovesIt() {
        RuleState moved = new RuleState(PolicyStatus.FINAL, NOV_1, false);
        List<HistoryRow> rows = List.of(
                row(1, PROPOSED, null, true, "2025-16554", SourceType.PRIMARY, LocalDate.of(2025, 8, 28)),
                row(2, FINAL, PROPOSED, true, "2026-14439", SourceType.PRIMARY, LocalDate.of(2026, 7, 17)),
                row(3, moved, FINAL, true, true, "2026-99950", SourceType.PRIMARY, LocalDate.of(2026, 9, 1)));

        Step delay = RuleTimelineService.steps(rows, moved, LocalDate.of(2026, 9, 2)).getLast();

        assertThat(delay.changes()).containsExactly(RuleChange.EFFECTIVE_DATE);
        assertThat(delay.previousEffectiveDate()).isEqualTo(SEP_15);
        assertThat(delay.effectiveDate()).isEqualTo(NOV_1);
        assertThat(delay.ownEffectiveDate()).isNull();
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
        return row(id, state, storedPrevious, applied, false, externalId, sourceType, published);
    }

    private static HistoryRow row(long id, RuleState state, RuleState storedPrevious, boolean applied,
            boolean aboutEffectiveDate, String externalId, SourceType sourceType, LocalDate published) {
        return new HistoryRow(id, 7L, state, storedPrevious, applied, false, aboutEffectiveDate,
                new RuleSource(id, externalId, sourceType, "https://example.gov/" + externalId, "Title", "Agency",
                        published),
                RecordedBy.INGESTION, Instant.parse("2026-11-06T00:00:00Z").plusSeconds(id));
    }
}
