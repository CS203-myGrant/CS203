package mygrant.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import mygrant.ingestion.classification.VisaTypeClassifier;

/** Checks title-priority classification and unrelated-document rejection. */
class VisaTypeClassifierTests {

    private final VisaTypeClassifier classifier = new VisaTypeClassifier();

    @Test
    void detectsF1AndJ1FromPolicyLanguage() {
        var result = classifier.classify("Academic student and exchange visitor admission",
                "This rule applies to F-1 academic students and J-1 exchange visitors.");

        assertThat(result.affectsF1()).isTrue();
        assertThat(result.affectsJ1()).isTrue();
        assertThat(result.affectsH1b()).isFalse();
        assertThat(result.visaTypes()).containsExactly("F-1", "J-1");
    }

    @Test
    void doesNotClassifyUnrelatedContent() {
        var result = classifier.classify("Agricultural reporting", "Quarterly crop report.");
        assertThat(result.visaTypes()).isEmpty();
    }

    @Test
    void ignoresIncidentalOtherVisasWhenTitleNamesAffectedCategories() {
        var result = classifier.classify(
                "Admission for Nonimmigrant Academic Students and Exchange Visitors",
                "The discussion compares F-1 and J-1 with H-1B specialty occupation workers.");

        assertThat(result.visaTypes()).containsExactly("F-1", "J-1");
        assertThat(result.affectsH1b()).isFalse();
    }

    @Test
    void fullTextDropsTypesMentionedFarLessOftenThanTheMainOne() {
        // Shaped like FR Doc 2026-18631: no signal in the title, and one footnote comparing F-1.
        var result = classifier.classify("Eliminating the Discretionary 60-Day Grace Period",
                "H-1B workers. H-1B employers. H-1B petitions. H-1B fees. H-1B grace period. "
                        + "Compare the F-1 grace period.");

        assertThat(result.visaTypes()).containsExactly("H-1B");
        assertThat(result.matchedSignals()).containsExactly(
                "F-1:dropped, 1 mentions is under 25% of the top count 5", "H-1B:h-1b x5");
    }

    @Test
    void fullTextKeepsTypesAtTheThreshold() {
        var result = classifier.classify("Nonimmigrant admission update",
                "H-1B. H-1B. H-1B. H-1B. F-1.");

        assertThat(result.visaTypes()).containsExactly("F-1", "H-1B");
        assertThat(result.matchedSignals()).containsExactly("F-1:f-1 x1", "H-1B:h-1b x4");
    }

}
