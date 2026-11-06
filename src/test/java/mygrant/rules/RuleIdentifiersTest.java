package mygrant.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Docket and RIN parsing from the saved Federal Register documents and from API fields. */
class RuleIdentifiersTest {

    @Test
    void readsTheDocketAndRinFromTheRealProposedAndFinalRule() throws IOException {
        var expected = List.of(RuleIdentifier.docket("ICEB-2025-0001"), RuleIdentifier.rin("1653-AA95"));

        assertThat(RuleIdentifiers.fromHeader(fixture("2025-16554"))).containsExactlyElementsOf(expected);
        assertThat(RuleIdentifiers.fromHeader(fixture("2026-14439"))).containsExactlyElementsOf(expected);
    }

    @Test
    void theSameTitledTwentyTwentyProposalHasItsOwnDocketAndRin() throws IOException {
        assertThat(RuleIdentifiers.fromHeader(fixture("2020-20845")))
                .containsExactly(RuleIdentifier.docket("ICEB-2019-0006"), RuleIdentifier.rin("1653-AA78"));
    }

    @Test
    void readsOnlyTheHeaderSoCitedRulemakingsAreIgnored() {
        String content = """
                [DHS Docket No. ICEB-2025-0001]
                RIN 1653-AA95

                SUMMARY: This rule replaces the proposal at [DHS Docket No. ICEB-2019-0006].
                RIN 1653-AA78
                """;

        assertThat(RuleIdentifiers.fromHeader(content))
                .containsExactly(RuleIdentifier.docket("ICEB-2025-0001"), RuleIdentifier.rin("1653-AA95"));
    }

    @Test
    void readsSeveralDocketsAndRinsInOneHeader() {
        String content = """
                [CIS No. 2741-23; DHS Docket No. USCIS-2023-0005]
                [Docket Nos. ETA-2023-0003 and ETA-2023-0004]
                RINs 1615-AC22, 1205-AC12

                AGENCY: U.S. Citizenship and Immigration Services
                """;

        assertThat(RuleIdentifiers.fromHeader(content)).containsExactly(
                RuleIdentifier.docket("USCIS-2023-0005"), RuleIdentifier.docket("ETA-2023-0003"),
                RuleIdentifier.docket("ETA-2023-0004"), RuleIdentifier.rin("1615-AC22"),
                RuleIdentifier.rin("1205-AC12"));
    }

    @Test
    void readsAnAgencyDocketWrittenWithASpaceAsOneDocket() throws IOException {
        // The real delay notice 2021-02665: "[CIS No. 2680-21; Docket No: USCIS 2020-0019]", and the
        // API's docket_ids say "Docket No: USCIS 2020-0019" too. The rest of its rulemaking says USCIS-2020-0019.
        var expected = List.of(RuleIdentifier.docket("USCIS-2020-0019"), RuleIdentifier.rin("1615-AC61"));
        assertThat(RuleIdentifiers.fromHeader(fixture("2021-02665"))).containsExactlyElementsOf(expected);
        assertThat(RuleIdentifiers.fromHeader(fixture("2021-00183"))).containsExactlyElementsOf(expected);
        assertThat(RuleIdentifiers.fromFederalRegisterApi(List.of("CIS No. 2680-21", "Docket No: USCIS 2020-0019"),
                List.of("1615-AC61"))).containsExactlyElementsOf(expected);
        // A lower-case word before a number is not an agency code.
        assertThat(RuleIdentifiers.docketsIn("Docket Nos. ICEB-2025-0001 and 2025-0002"))
                .containsExactly("ICEB-2025-0001", "2025-0002");
    }

    @Test
    void documentsWithoutAHeaderHaveNoIdentifiers() {
        assertThat(RuleIdentifiers.fromHeader("F-1 students must report changes within 10 days.")).isEmpty();
        assertThat(RuleIdentifiers.fromHeader(null)).isEmpty();
    }

    @Test
    void dropsTheAgencyLabelFromApiDocketsAndIgnoresOtherNumbers() {
        // The Federal Register API returns docket_ids with the label, as in the header.
        var identifiers = RuleIdentifiers.fromFederalRegisterApi(
                List.of("DHS Docket No. ICEB-2025-0001", "CIS No. 2741-23", "uscis-2023-0005"),
                List.of("1653-AA95", " 1615-ac22 "));

        assertThat(identifiers).containsExactly(RuleIdentifier.docket("ICEB-2025-0001"),
                RuleIdentifier.docket("USCIS-2023-0005"), RuleIdentifier.rin("1653-AA95"),
                RuleIdentifier.rin("1615-AC22"));
        assertThat(RuleIdentifiers.fromFederalRegisterApi(null, null)).isEmpty();
    }

    @Test
    void readsFederalRegisterDocumentAndRegulationsGovDocketLinks() {
        var identifiers = RuleIdentifiers.fromLinks(List.of(
                "https://www.federalregister.gov/d/2026-14439",
                "https://www.federalregister.gov/documents/2025/08/28/2025-16554/establishing-a-fixed-time-period",
                "https://www.regulations.gov/docket/ICEB-2025-0001",
                "https://www.regulations.gov/document/ICEB-2025-0001-0042",
                "https://www.theguardian.com/us-news/2026/oct/01/student-visas",
                "https://www.federalregister.gov/agencies/homeland-security-department"));

        assertThat(identifiers).containsExactly(RuleIdentifier.federalRegisterDocument("2026-14439"),
                RuleIdentifier.federalRegisterDocument("2025-16554"), RuleIdentifier.docket("ICEB-2025-0001"));
    }

    @Test
    void readsLabelledIdentifiersAndWrittenOutLinksFromNewsText() {
        String text = "The rule (DHS Docket No. ICEB-2025-0001, RIN 1653-AA95) is at "
                + "https://www.federalregister.gov/d/2026-14439. F-1 and J-1 students, docket 12, rin 5.";

        assertThat(RuleIdentifiers.fromNewsText(text)).containsExactly(RuleIdentifier.docket("ICEB-2025-0001"),
                RuleIdentifier.rin("1653-AA95"), RuleIdentifier.federalRegisterDocument("2026-14439"));
        assertThat(RuleIdentifiers.fromNewsText("A report discusses F-1 students.")).isEmpty();
    }

    static String fixture(String documentNumber) throws IOException {
        try (InputStream in = RuleIdentifiersTest.class.getResourceAsStream("/federal-register/" + documentNumber + ".txt")) {
            assertThat(in).as("fixture for %s", documentNumber).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
