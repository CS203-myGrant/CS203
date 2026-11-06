package mygrant.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import mygrant.policies.PolicyStatus;
import mygrant.rules.RuleChange;
import mygrant.rules.RuleState;

/** The wording of rule-change alerts: what moved, not "New policy". */
class RuleChangeMessageTest {

    private static final List<String> VISAS = List.of("F-1", "J-1");
    private static final LocalDate SEP_15 = LocalDate.of(2026, 9, 15);
    private static final LocalDate NOV_1 = LocalDate.of(2026, 11, 1);

    @Test
    void aStageMoveWithItsFirstEffectiveDate() {
        assertThat(message(new RuleState(PolicyStatus.PROPOSED, null, false), new RuleState(PolicyStatus.FINAL, SEP_15, false)))
                .isEqualTo("Rule update for F-1, J-1: moved from proposed to final, effective 15 Sep 2026: Rule");
    }

    @Test
    void aStageMoveWithoutADate() {
        assertThat(message(new RuleState(PolicyStatus.PROPOSED, null, false), new RuleState(PolicyStatus.WITHDRAWN, null, false)))
                .isEqualTo("Rule update for F-1, J-1: moved from proposed to withdrawn: Rule");
    }

    @Test
    void anEffectiveDateMove() {
        assertThat(message(new RuleState(PolicyStatus.FINAL, SEP_15, false), new RuleState(PolicyStatus.FINAL, NOV_1, false)))
                .isEqualTo("Rule update for F-1, J-1: effective date moved from 15 Sep 2026 to 1 Nov 2026: Rule");
    }

    @Test
    void aFirstEffectiveDateOnAFinalRule() {
        assertThat(message(new RuleState(PolicyStatus.FINAL, null, false), new RuleState(PolicyStatus.FINAL, NOV_1, false)))
                .isEqualTo("Rule update for F-1, J-1: effective date set to 1 Nov 2026: Rule");
    }

    @Test
    void aDisputeKeepsTheOfficialStatus() {
        assertThat(message(new RuleState(PolicyStatus.FINAL, NOV_1, false), new RuleState(PolicyStatus.FINAL, NOV_1, true)))
                .isEqualTo("Rule update for F-1, J-1: status disputed: news reports litigation or a stay (The Guardian). "
                        + "The official status is still final, effective 1 Nov 2026: Rule");
    }

    private static String message(RuleState previous, RuleState current) {
        return RuleChangeMessage.of(VISAS, "Rule", previous, current, RuleChange.between(previous, current),
                previous.disputed() == current.disputed() ? "Homeland Security Department" : "The Guardian");
    }
}
