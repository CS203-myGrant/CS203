package mygrant.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import mygrant.ingestion.classification.VisaClassification;
import mygrant.ingestion.classification.VisaTypeClassifier;

/**
 * Runs real Federal Register documents through classification and the impact rules, the
 * same two steps an import takes. Each fixture is the document's raw_text_url download.
 */
class FederalRegisterFixtureTests {

    private final VisaTypeClassifier classifier = new VisaTypeClassifier();
    private final PolicyImpactRules rules = new PolicyImpactRules();

    record Expected(String documentNumber, String title, List<String> visaTypes,
            Map<String, Set<ProfileField>> fieldsByVisaType) {

        @Override
        public String toString() {
            return documentNumber;
        }
    }

    static Stream<Expected> documents() {
        String fixedAdmissionTitle = "Establishing a Fixed Time Period of Admission and an Extension of Stay "
                + "Procedure for Nonimmigrant Academic Students, Exchange Visitors, and Representatives of "
                + "Foreign Information Media";
        Set<ProfileField> programAndLevel = Set.of(ProfileField.PROGRAM_END_DATE, ProfileField.ACADEMIC_LEVEL);
        return Stream.of(
                // Revises forms schools file with SEVP, so students have nothing to do.
                new Expected("2025-20932", "Agency Information Collection Activities; Revision of a Currently "
                        + "Approved Collection: Student and Exchange Visitor Information System (SEVIS)",
                        List.of("F-1", "J-1"), Map.of("F-1", Set.of(), "J-1", Set.of())),
                new Expected("2020-20845", fixedAdmissionTitle, List.of("F-1", "J-1"),
                        Map.of("F-1", programAndLevel, "J-1", programAndLevel)),
                // The title names no visa type; the F-1 and J-1 grace periods appear only in a
                // footnote comparing them with the worker grace period this rule removes.
                new Expected("2026-18631", "Eliminating the Discretionary 60-Day Grace Period",
                        List.of("H-1B"), Map.of("H-1B", Set.of(ProfileField.EMPLOYMENT))),
                new Expected("2026-14439", fixedAdmissionTitle, List.of("F-1", "J-1"),
                        Map.of("F-1", programAndLevel, "J-1", programAndLevel)),
                new Expected("2025-16554", fixedAdmissionTitle, List.of("F-1", "J-1"),
                        Map.of("F-1", programAndLevel, "J-1", programAndLevel)),
                // Known over-flag: the list only matters to students in the listed STEM programs,
                // but the profile has no field of study to narrow it by. Narrowing it needs a new
                // profile field, which is separate work.
                new Expected("2024-16127",
                        "Update to the Department of Homeland Security STEM Designated Degree Program List",
                        List.of("F-1"), Map.of("F-1", Set.of(ProfileField.PROGRAM_END_DATE))),
                // Employers pay this fee on new cap-subject petitions; the travel wording is background.
                new Expected("2026-17324", "Fee for Certain H-1B Petitions",
                        List.of("H-1B"), Map.of("H-1B", Set.of())),
                // Also an employer-paid fee; "entry-exit" matches only because it is in the fee's name.
                new Expected("2024-12396", "9-11 Response and Biometric Entry-Exit Fee for H-1B and L-1 Visas",
                        List.of("H-1B"), Map.of("H-1B", Set.of())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("documents")
    void classifiesAndFlagsTheRealDocument(Expected expected) throws IOException {
        String content = fixture(expected.documentNumber());

        VisaClassification classification = classifier.classify(expected.title(), content);
        assertThat(classification.visaTypes()).containsExactlyElementsOf(expected.visaTypes());

        Map<String, Set<ProfileField>> fields =
                rules.affectedFieldsByVisaType(classification.visaTypes(), expected.title(), content);
        assertThat(fields).isEqualTo(expected.fieldsByVisaType());
    }

    private String fixture(String documentNumber) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/federal-register/" + documentNumber + ".txt")) {
            assertThat(in).as("fixture for %s", documentNumber).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
