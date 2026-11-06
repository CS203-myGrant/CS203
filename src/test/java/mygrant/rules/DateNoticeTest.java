package mygrant.rules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Which final documents are about the effective date, read from the real and synthetic headers. */
class DateNoticeTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            2023-23381                     | Notice of proposed rulemaking.
            2024-01770                     | Final rulemaking.
            2024-29354                     | Final rule.
            2026-14439                     | Final rule.
            synthetic-2026-99950-delay     | Final rule; delay of effective date.
            """)
    void readsTheActionLineFromTheStoredHeader(String fixture, String action) {
        assertThat(DateNotice.action(RuleFixtures.text("federal-register/" + fixture + ".txt"))).contains(action);
    }

    @Test
    void theTwoH1bFinalRulesAreNotAboutTheDateButTheDelayNoticeIs() {
        assertThat(DateNotice.concernsEffectiveDate(RuleFixtures.H1B_REGISTRATION_TITLE,
                RuleFixtures.text("federal-register/" + RuleFixtures.H1B_REGISTRATION_RULE + ".txt"))).isFalse();
        assertThat(DateNotice.concernsEffectiveDate(RuleFixtures.H1B_TITLE,
                RuleFixtures.text("federal-register/" + RuleFixtures.H1B_FINAL_RULE + ".txt"))).isFalse();
        assertThat(DateNotice.concernsEffectiveDate(RuleFixtures.dateMove().title(),
                RuleFixtures.dateMove().content())).isTrue();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Final rule; postponement of effective date.
            Interim final rule; delay of effective date and request for comments.
            Final rule; correction.
            Final rule; correcting amendments.
            Final rule; change of effective date.
            """)
    void anActionAboutTheDateCounts(String action) {
        assertThat(DateNotice.concernsEffectiveDate("Some Rule", header(action))).isTrue();
    }

    @Test
    void anActionCanWrapOntoTheNextLine() {
        String content = "[DHS Docket No. USCIS-2023-0005]\n\nACTION: Final rule; partial delay of\neffective date.\n\n"
                + "SUMMARY: Text.";
        assertThat(DateNotice.action(content)).contains("Final rule; partial delay of effective date.");
        assertThat(DateNotice.concernsEffectiveDate("Some Rule", content)).isTrue();
    }

    @Test
    void theTitlesStageSuffixCountsButNotTheMainTitle() {
        assertThat(DateNotice.concernsEffectiveDate("Some Rule; Correction", "No header")).isTrue();
        assertThat(DateNotice.concernsEffectiveDate("Some Rule; Delay of Effective Date", null)).isTrue();
        assertThat(DateNotice.concernsEffectiveDate("Effective Dates for Student Reporting", header("Final rule."))).isFalse();
    }

    @Test
    void onlyTheHeaderIsRead() {
        // The body of a final rule discusses its effective date; that does not make it a delay notice.
        String content = "ACTION: Final rule.\n\nSUMMARY: This rule is effective on January 17, 2025.\n\n"
                + "ACTION: Final rule; delay of effective date.";
        assertThat(DateNotice.concernsEffectiveDate("Some Rule", content)).isFalse();
        assertThat(DateNotice.action("SUMMARY: no action line here.")).isEmpty();
        assertThat(DateNotice.action(null)).isEmpty();
    }

    private static String header(String action) {
        return "[DHS Docket No. USCIS-2023-0005]\nRIN 1615-AC70\n\nACTION: " + action + "\n\n---\n\nSUMMARY: Text.";
    }
}
