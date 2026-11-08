package mygrant.impact;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class ConsolidatedImpactAssessmentTests {

    @Test
    void combinesNetUrgencyCitationsAndUncertaintyWithoutCollapsingAtomicBreakdown() {
        var visa = impact(1, "H-1B policy", ImpactUrgency.HIGH, 90, false,
                List.of(), List.of(new ImpactFeedService.Citation("H-1B policy", "https://source.gov/shared")));
        var nationality = impact(2, "Nationality policy", ImpactUrgency.MEDIUM, 55, true,
                List.of("countryOfCitizenship"), List.of(
                        new ImpactFeedService.Citation("Nationality policy", "https://source.gov/shared"),
                        new ImpactFeedService.Citation("Section 2", "https://source.gov/section-2")));

        var consolidated = ImpactFeedService.aggregate(List.of(visa, nationality));

        assertThat(consolidated.netStatus()).isEqualTo(ImpactUrgency.HIGH);
        assertThat(consolidated.citations()).extracting(ImpactFeedService.Citation::url)
                .containsExactly("https://source.gov/shared", "https://source.gov/section-2");
        assertThat(consolidated.uncertaintyNotes()).containsExactly(
                "Nationality policy: countryOfCitizenship",
                "Nationality policy: assessment confidence is low (55%).",
                "Nationality policy: human review is recommended for this assessment.");
        assertThat(consolidated.breakdown()).containsExactly(visa, nationality);
    }

    private ImpactFeedService.ImpactView impact(long id, String title, ImpactUrgency urgency,
            int confidence, boolean review, List<String> missing,
            List<ImpactFeedService.Citation> citations) {
        return new ImpactFeedService.ImpactView(id, id, title, "Agency", urgency.name(), urgency, review,
                title + " assessment", confidence, confidence >= 80 ? "HIGH" : "LOW", List.of("Reason"), missing,
                citations, List.of(), false, null, false, Instant.EPOCH, Instant.EPOCH, 1, "Disclaimer");
    }
}
