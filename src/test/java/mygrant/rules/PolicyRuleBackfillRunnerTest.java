package mygrant.rules;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

/** The startup hook: a failing backfill is logged and never stops the application. */
class PolicyRuleBackfillRunnerTest {

    private final PolicyRuleBackfill backfill = mock(PolicyRuleBackfill.class);

    @Test
    void aFailingBackfillDoesNotThrow() {
        when(backfill.run()).thenThrow(new IllegalStateException("database unavailable"));

        assertThatCode(() -> new PolicyRuleBackfillRunner(backfill, true).backfillOnStartup())
                .doesNotThrowAnyException();
        verify(backfill).run();
    }

    @Test
    void doesNothingWhenSwitchedOff() {
        new PolicyRuleBackfillRunner(backfill, false).backfillOnStartup();

        verify(backfill, never()).run();
    }
}
