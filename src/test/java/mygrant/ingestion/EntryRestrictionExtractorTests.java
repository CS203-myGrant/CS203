package mygrant.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import mygrant.ingestion.classification.EntryRestrictionExtractor;
import mygrant.policies.RestrictionScope;

class EntryRestrictionExtractorTests {
    private final EntryRestrictionExtractor extractor = new EntryRestrictionExtractor();

    @Test
    void extractsExplicitFullAndPartialCountryRulesAndPreservesExemptionSource() {
        String body = """
                Proclamation restricting and limiting the entry of nationals.
                Section 2. Continued Full Suspension of Entry. The entry of nationals of Afghanistan and Burma is suspended.
                Section 3. Partial Suspension of Entry. The entry of nationals of Cambodia is partially suspended.
                Section 6. Exceptions to this proclamation. This section does not apply to lawful permanent residents.
                """;

        var extraction = extractor.extract("Entry proclamation", body, "https://example.gov/p/1");

        assertThat(extraction.proclamation()).isTrue();
        assertThat(extraction.requiresReview()).isFalse();
        assertThat(extraction.restrictions()).extracting(rule -> rule.getCountryCode())
                .containsExactlyInAnyOrder("AF", "MM", "KH");
        assertThat(extraction.restrictions()).filteredOn(rule -> rule.getCountryCode().equals("AF"))
                .extracting(rule -> rule.getScope()).containsExactly(RestrictionScope.FULL);
        assertThat(extraction.restrictions()).filteredOn(rule -> rule.getCountryCode().equals("KH"))
                .extracting(rule -> rule.getScope()).containsExactly(RestrictionScope.PARTIAL);
        assertThat(extraction.restrictions()).allSatisfy(rule ->
                assertThat(rule.getExemptionWording()).contains("lawful permanent residents"));
        assertThat(extraction.sourceReferences()).allMatch(url -> url.startsWith("https://example.gov/p/1#Section-"));
    }

    @Test
    void recognizesProclamationWithoutGuessingWhenCountryListCannotBeExtracted() {
        var extraction = extractor.extract("Proclamation on entry restrictions",
                "This proclamation suspends entry under conditions to be determined.", "https://example.gov/p/2");

        assertThat(extraction.proclamation()).isTrue();
        assertThat(extraction.requiresReview()).isTrue();
        assertThat(extraction.affectedCountries()).isEmpty();
        assertThat(extraction.restrictions()).isEmpty();
    }

    @Test
    void recognizesOnlyTheExplicitPalestinianAuthorityTravelDocumentCaseWithoutGuessingCountries() {
        var extraction = extractor.extract("Entry proclamation",
                "This proclamation suspends entry for individuals using travel documents issued or endorsed by the PA.",
                "https://example.gov/p/3");

        assertThat(extraction.proclamation()).isTrue();
        assertThat(extraction.specialDocumentHolderRule()).isTrue();
        assertThat(extraction.requiresReview()).isFalse();
        assertThat(extraction.affectedCountries()).isEmpty();
    }

    @Test
    void supportsFederalRegisterSecAbbreviation() {
        var extraction = extractor.extract("Proclamation restricting entry",
                "Sec. 4. Full Suspension of Entry. Entry of nationals of Afghanistan is suspended.",
                "https://example.gov/p/4");
        assertThat(extraction.affectedCountries()).containsExactly("AF");
        assertThat(extraction.restrictions()).singleElement()
                .extracting(rule -> rule.getScope()).isEqualTo(RestrictionScope.FULL);
    }

    @Test
    void ignoresDocumentsThatAreNotEntryProclamations() {
        var extraction = extractor.extract("Student visa update", "A rule about F-1 students.", "https://example.gov/rule");
        assertThat(extraction.proclamation()).isFalse();
    }
}
