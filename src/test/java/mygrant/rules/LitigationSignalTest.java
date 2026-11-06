package mygrant.rules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Litigation phrasing in news; "stay" alone, the name Sue, and an agency's own postponement never count. */
class LitigationSignalTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "A federal judge granted a preliminary injunction against the rule.",
            "Universities are suing over F-1 student admission limits.",
            "A group of universities filed a lawsuit over the rule.",
            "The court stayed the rule pending appeal.",
            "Plaintiffs asked for a stay of the rule.",
            "Judge blocks rule ending open-ended stays",
            "The rule was blocked by a federal judge on Tuesday.",
            "A temporary restraining order was issued.",
            "The final rule was vacated.",
            "A federal court in Boston paused the rule.",
            "The appeals court halted the rule."})
    void reportsLitigation(String text) {
        assertThat(LitigationSignal.reports(text)).isTrue();
    }

    /** Real headlines about the D/S rule (2026-14439) and the court order postponing it. */
    @ParameterizedTest
    @ValueSource(strings = {
            "US judge blocks Trump limits on how long foreign students, journalists can stay",
            "Court Temporarily Blocks Rule Ending 'Duration of Status' for International Students",
            "US coalition files lawsuit to challenge rule ending Duration of Status admissions for international students",
            "New Lawsuit Seeks to Block DHS Fixed-Term Visa Rule for International Students and Scholars",
            "Judge blocks four-year student visa cap, one day before it would take effect",
            "US federal judge blocks DHS rule restricting visas for foreign students, researchers, journalists",
            "Federal Court Postpones Effective Date of Rule Ending \"Duration of Status\""})
    void reportsRealHeadlinesAboutTheCourtOrder(String headline) {
        assertThat(LitigationSignal.reports(headline)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Important Update for F-1 Students and J-1 Exchange Visitors: DHS Fixed-Period Admission Rule Takes "
                    + "Effect September 15, 2026",
            "DHS postpones the effective date of its rule",
            "Students will be admitted for a fixed length of stay.",
            "Extensions of stay will need an application.",
            "J-1 visitors may stay for the duration of their program.",
            "DHS published the final rule in July.",
            "Pursue a degree in the United States.",
            "Immigration lawyer Sue Lee said F-1 students should keep their I-20 up to date.",
            "SUE LEE, an adviser at the university, explained the new admission period.",
            // A court staying out of a dispute is not a court stay.
            "Supreme Court stays out of student visa fight",
            "The appeals court stayed out of the dispute over the rule.",
            // The action must be within three words of the court or judge.
            "The immigration judge said the student's stays were lawful."})
    void ignoresOrdinaryImmigrationText(String text) {
        assertThat(LitigationSignal.reports(text)).isFalse();
    }
}
