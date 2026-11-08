package mygrant.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import mygrant.ingestion.classification.EntryRestrictionExtractor;
import mygrant.ingestion.classification.VisaTypeClassifier;
import mygrant.policies.RestrictionScope;

class EntryRestrictionExtractorTests {
    private final EntryRestrictionExtractor extractor = new EntryRestrictionExtractor();

    @Test
    void historical10949FixtureKeepsFullPartialScopesAndSourceDates() throws IOException {
        String source = fixture("proclamation-10949.txt");
        var extraction = extractor.extract("Proclamation 10949 Restricting the Entry of Foreign Nationals",
                source, "https://www.federalregister.gov/documents/2025/06/10/2025-10669");
        assertThat(source).contains("Publication date: 2025-06-10", "Effective date: 2025-06-09");
        assertThat(extraction.proclamation()).isTrue();
        assertThat(extraction.requiresReview()).isFalse();
        assertThat(extraction.restrictions()).anySatisfy(rule -> {
            assertThat(rule.getCountryCode()).isEqualTo("AF");
            assertThat(rule.getScope()).isEqualTo(RestrictionScope.FULL);
        });
        assertThat(extraction.restrictions()).anySatisfy(rule -> {
            assertThat(rule.getCountryCode()).isEqualTo("BI");
            assertThat(rule.getScope()).isEqualTo(mygrant.policies.RestrictionScope.PARTIAL);
        });
        assertThat(extraction.restrictions()).allSatisfy(rule -> assertThat(rule.getExemptionWording())
                .contains("dual nationals", "visa categories"));
        assertThat(extraction.sourceReferences()).isNotEmpty();
    }

    @Test
    void historical10973FixtureIsVisaScopedNotAnUnparsedNationalityRestriction() throws IOException {
        String source = fixture("proclamation-10973.txt");
        var extraction = extractor.extract("Restriction on Entry of Certain Nonimmigrant Workers", source,
                "https://www.federalregister.gov/documents/2025/09/24/2025-18601");
        assertThat(source).contains("Publication date: 2025-09-24", "Effective date: 2025-09-21");
        assertThat(extraction.proclamation()).isFalse();
        assertThat(extraction.requiresReview()).isFalse();
        assertThat(new VisaTypeClassifier().classify("Restriction on Entry of Certain Nonimmigrant Workers", source)
                .visaTypes()).contains("H-1B");
    }

    private String fixture(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/federal-register/" + name)) {
            assertThat(input).as("historical source fixture %s", name).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

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
