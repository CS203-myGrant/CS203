package mygrant.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Which final documents are about the effective date, read from the real and synthetic headers,
 * and the new date such a document gives in its DATES paragraph.
 */
class DateNoticeTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            2023-23381                     | Notice of proposed rulemaking.
            2024-01770                     | Final rulemaking.
            2024-29354                     | Final rule.
            2026-14439                     | Final rule.
            synthetic-2026-99950-delay     | Final rule; delay of effective date.
            2021-02665                     | Final rule; delay of effective date; request for comments.
            2021-27714                     | Final rule; withdrawal.
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

    @Test
    void theRealDelayNoticeGivesItsNewDateInDatesNotInEffectiveOn() {
        String delay = RuleFixtures.text("federal-register/2021-02665.txt");

        assertThat(DateNotice.concernsEffectiveDate(RuleFixtures.AC61_DELAY_TITLE, delay)).isTrue();
        assertThat(DateNotice.dates(delay)).contains("As of February 8, 2021, the effective date of the final rule "
                + "published January 8, 2021, at 86 FR 1676, is delayed to December 31, 2021. DHS is accepting "
                + "public comments on this delay until March 10, 2021.");
        // Not 8 Jan 2021, the API's effective_on, and not 10 Mar 2021, the end of the comment period.
        assertThat(DateNotice.newEffectiveDate(delay)).contains(LocalDate.of(2021, 12, 31));
    }

    @Test
    void theSyntheticDelayNoticeGivesItsNewDateInDates() {
        assertThat(DateNotice.newEffectiveDate(RuleFixtures.dateMove().content())).contains(RuleFixtures.MOVED_EFFECTIVE);
    }

    @Test
    void theFinalRulesAndTheWithdrawalAreNotAboutTheDate() {
        assertThat(DateNotice.concernsEffectiveDate(RuleFixtures.AC61_TITLE,
                RuleFixtures.text("federal-register/2021-00183.txt"))).isFalse();
        // "which was delayed by the final rule" is in its DATES, but its ACTION is a withdrawal.
        assertThat(DateNotice.concernsEffectiveDate(RuleFixtures.AC61_WITHDRAWAL_TITLE,
                RuleFixtures.text("federal-register/2021-27714.txt"))).isFalse();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            The effective date of the final rule is delayed until\\nMarch 21, 2027.                  | 2027-03-21
            The effective date is postponed to April 1, 2027, to allow schools to prepare.           | 2027-04-01
            The effective date is postponed until June 30 2027.                                       | 2027-06-30
            The new effective date is January 15, 2028.                                               | 2028-01-15
            The final rule's new effective date will be on May 3, 2027.                               | 2027-05-03
            """)
    void readsTheNewDateInTheUsualWordings(String dates, LocalDate expected) {
        assertThat(DateNotice.newEffectiveDate(withDates(dates.replace("\\n", "\n")))).contains(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            The effective date of the final rule is delayed.
            DHS is accepting comments on this delay until March 10, 2021.
            This correction is effective February 1, 2021.
            The effective date is delayed to March 1, 2027; the compliance date is postponed to June 1, 2027.
            The effective date is delayed until February 30, 2027.
            """)
    void readsNoDateWhenThereIsNoneOrMoreThanOne(String dates) {
        assertThat(DateNotice.newEffectiveDate(withDates(dates))).isEmpty();
    }

    @Test
    void aDelayAnnouncesANewDateButACorrectionAloneDoesNot() {
        assertThat(DateNotice.announcesNewDate(RuleFixtures.AC61_DELAY_TITLE,
                RuleFixtures.text("federal-register/2021-02665.txt"))).isTrue();
        assertThat(DateNotice.announcesNewDate("Some Rule", header("Final rule; change of effective date."))).isTrue();
        assertThat(DateNotice.announcesNewDate("Some Rule; Postponement of Effective Date", null)).isTrue();
        // Still about the date, so never a separate final rule, but no new date is expected.
        assertThat(DateNotice.concernsEffectiveDate("Some Rule", header("Final rule; correction."))).isTrue();
        assertThat(DateNotice.announcesNewDate("Some Rule", header("Final rule; correction."))).isFalse();
        assertThat(DateNotice.announcesNewDate("Some Rule; Correction", "No header")).isFalse();
    }

    @Test
    void datesEndsAtTheNextLabelAndNeedsADatesParagraph() {
        String content = header("Final rule; delay of effective date.").replace("SUMMARY: Text.",
                "SUMMARY: DHS delays the rule.\n\nDATES: The effective date is delayed to\nMarch 1, 2027.\n\n"
                        + "ADDRESSES: The rule is delayed to April 1, 2027, says nobody.\n");
        assertThat(DateNotice.dates(content)).contains("The effective date is delayed to March 1, 2027.");
        assertThat(DateNotice.newEffectiveDate(content)).contains(LocalDate.of(2027, 3, 1));
        assertThat(DateNotice.dates(header("Final rule; delay of effective date."))).isEmpty();
        assertThat(DateNotice.newEffectiveDate(header("Final rule; delay of effective date."))).isEmpty();
    }

    private static String withDates(String dates) {
        return header("Final rule; delay of effective date.").replace("SUMMARY: Text.",
                "SUMMARY: Text.\n\nDATES: " + dates + "\n\nFOR FURTHER INFORMATION CONTACT: Someone.\n");
    }

    private static String header(String action) {
        return "[DHS Docket No. USCIS-2023-0005]\nRIN 1615-AC70\n\nACTION: " + action + "\n\n---\n\nSUMMARY: Text.";
    }
}
