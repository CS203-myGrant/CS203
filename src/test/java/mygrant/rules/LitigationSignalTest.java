package mygrant.rules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Litigation phrasing in news; "stay" alone never counts. */
class LitigationSignalTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "A federal judge granted a preliminary injunction against the rule.",
            "Universities sue over F-1 student admission limits",
            "The court stayed the rule pending appeal.",
            "Plaintiffs asked for a stay of the rule.",
            "Judge blocks rule ending open-ended stays",
            "The rule was blocked by a federal judge on Tuesday.",
            "A temporary restraining order was issued.",
            "The final rule was vacated."})
    void reportsLitigation(String text) {
        assertThat(LitigationSignal.reports(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Students will be admitted for a fixed length of stay.",
            "Extensions of stay will need an application.",
            "J-1 visitors may stay for the duration of their program.",
            "DHS published the final rule in July.",
            "Pursue a degree in the United States."})
    void ignoresOrdinaryImmigrationText(String text) {
        assertThat(LitigationSignal.reports(text)).isFalse();
    }
}
