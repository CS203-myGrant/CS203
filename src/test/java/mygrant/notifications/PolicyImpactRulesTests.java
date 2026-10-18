package mygrant.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Verifies the keyword rules that map policy text to affected profile fields. */
class PolicyImpactRulesTests {

    private final PolicyImpactRules rules = new PolicyImpactRules();

    @Test
    void fixedPeriodOfAdmissionAffectsProgramEndDate() {
        assertThat(fieldsFor("F-1", "Fixed Time Period of Admission for F-1 Students", ""))
                .containsExactly(ProfileField.PROGRAM_END_DATE);
    }

    @Test
    void practicalTrainingAffectsOnlyTheProgramEndDate() {
        assertThat(fieldsFor("F-1", "STEM OPT changes", "Optional Practical Training is revised."))
                .containsExactly(ProfileField.PROGRAM_END_DATE);
    }

    @Test
    void practicalTrainingDoesNotAffectOtherVisaCategories() {
        assertThat(fieldsFor("H-1B", "STEM OPT changes", "Optional Practical Training is revised."))
                .isEmpty();
        assertThat(fieldsFor("J-1", "STEM OPT changes", "Optional Practical Training is revised."))
                .isEmpty();
    }

    @Test
    void durationOfStatusAffectsStudentAndExchangeVisitorProgramDates() {
        assertThat(fieldsFor("J-1", "Duration of status is replaced", ""))
                .containsExactly(ProfileField.PROGRAM_END_DATE);
        assertThat(fieldsFor("H-1B", "Duration of status is replaced", "")).isEmpty();
    }

    @Test
    void degreeLevelAffectsAcademicLevel() {
        assertThat(fieldsFor("F-1", "Eligibility by degree level", ""))
                .containsExactly(ProfileField.ACADEMIC_LEVEL);
    }

    @Test
    void entryExitRulesThatActOnTheTravellerAffectUpcomingTravel() {
        // Made-up wording: a rule that changes what a worker must do when crossing the border.
        assertThat(fieldsFor("H-1B", "Biometric Entry-Exit Collection for H-1B Workers",
                "Each H-1B worker must provide biometrics at the port of entry on every departure "
                        + "and re-entry."))
                .containsExactly(ProfileField.UPCOMING_TRAVEL);
    }

    @Test
    void entryExitNamedOnlyInAFeeAffectsNoOne() {
        // Wording taken from FR Doc 2024-12396, a fee employers pay; "entry-exit" is in its name,
        // and its passages about the exit system describe the agency, not a traveller.
        assertThat(fieldsFor("H-1B", "9-11 Response and Biometric Entry-Exit Fee for H-1B and L-1 Visas",
                "CBP is responsible for implementing an integrated and automated entry-exit system that "
                        + "matches biographic data and biometrics of noncitizens entering and departing "
                        + "the United States."))
                .isEmpty();
        // Wording taken from FR Doc 2026-17324, also paid by employers; this sentence is background.
        assertThat(fieldsFor("H-1B", "Fee for Certain H-1B Petitions",
                "A citizen of a foreign country who seeks to travel to the United States generally "
                        + "must first obtain a U.S. visa. See CBP 9-11 Response and Biometric Entry-Exit Fee."))
                .isEmpty();
    }

    @Test
    void travelContextFurtherThanTheWindowDoesNotCount() {
        String filler = "x".repeat(PolicyImpactRules.CONTEXT_WINDOW) + " ";
        assertThat(fieldsFor("H-1B", "Border update",
                "Every departure is recorded. " + filler + "The entry-exit system is funded."))
                .isEmpty();
    }

    @Test
    void h1bPolicyWithoutTravelSignalsAffectsNothing() {
        assertThat(fieldsFor("H-1B", "Fee for Certain H-1B Petitions",
                "Employers filing a petition pay an additional fee toward full cost recovery."))
                .isEmpty();
    }

    @Test
    void travelSignalsDoNotAffectStudentOrExchangeVisitorCategories() {
        // Shaped like FR Doc 2025-20932: classified F-1/J-1 only, so no H-1B reader exists
        // even when the text carries every H-1B travel signal.
        Map<String, Set<ProfileField>> byVisaType = rules.affectedFieldsByVisaType(List.of("F-1", "J-1"),
                "Biometric entry and exit for students",
                "Arrival and departure records, travel documents, and travel to the United States.");

        assertThat(byVisaType).containsOnlyKeys("F-1", "J-1");
        assertThat(byVisaType.values()).allSatisfy(fields ->
                assertThat(fields).doesNotContain(ProfileField.UPCOMING_TRAVEL));
    }

    @Test
    void workerGracePeriodAffectsH1bEmploymentAndNotStudentProgramDates() {
        // Wording shaped like FR Doc 2026-18631, Eliminating the Discretionary 60-Day Grace Period.
        Map<String, Set<ProfileField>> byVisaType = rules.affectedFieldsByVisaType(
                List.of("F-1", "J-1", "H-1B"),
                "Eliminating the Discretionary 60-Day Grace Period",
                "DHS removes the grace period that followed the cessation of an H-1B worker's employment.");

        assertThat(byVisaType.get("H-1B")).containsExactly(ProfileField.EMPLOYMENT);
        assertThat(byVisaType.get("F-1")).isEmpty();
        assertThat(byVisaType.get("J-1")).isEmpty();
    }

    @Test
    void studentGracePeriodStillAffectsProgramEndDate() {
        assertThat(fieldsFor("F-1", "Grace period update",
                "An F-1 student has a 60-day grace period after program completion."))
                .containsExactly(ProfileField.PROGRAM_END_DATE);
    }

    @Test
    void contextWordsFurtherThanTheWindowDoNotCount() {
        String filler = "x".repeat(PolicyImpactRules.CONTEXT_WINDOW) + " ";
        assertThat(fieldsFor("F-1", "Status update",
                "An F-1 student remains. " + filler + "The grace period ends."))
                .isEmpty();
    }

    @Test
    void changeOfEmployerAffectsNoOne() {
        // Wording taken from FR Doc 2024-12396, which exempts these petitions from its fee.
        String exemption = "Extension-of-stay petitions that do not involve a change of employer.";
        assertThat(fieldsFor("F-1", "Fee update", exemption)).isEmpty();
        assertThat(fieldsFor("H-1B", "Fee update", exemption)).isEmpty();
    }

    @Test
    void signalsMatchAcrossTheLineWrappingInDownloadedPolicyText() {
        // Federal Register raw text is hard-wrapped, so a signal arrives split over two lines.
        assertThat(fieldsFor("F-1", "Admission periods",
                "This rule ends duration of \nstatus for academic students."))
                .containsExactly(ProfileField.PROGRAM_END_DATE);
    }

    @Test
    void signalsMatchWholeWordsOnly() {
        assertThat(fieldsFor("F-1", "Candidate certain requirements", "")).isEmpty();
    }

    @Test
    void unrelatedPolicyAffectsNothing() {
        assertThat(fieldsFor("F-1", "Fee schedule update", "Adjusts filing fees.")).isEmpty();
        assertThat(fieldsFor("F-1", "Fee schedule update", null)).isEmpty();
    }

    @Test
    void unknownOrMissingVisaTypeAffectsNothing() {
        assertThat(fieldsFor("O-1", "Duration of status is replaced", "")).isEmpty();
        assertThat(fieldsFor(null, "Duration of status is replaced", "")).isEmpty();
    }

    @Test
    void visaTypeComparisonIgnoresCaseAndWhitespace() {
        assertThat(fieldsFor(" f-1 ", "Duration of status is replaced", ""))
                .containsExactly(ProfileField.PROGRAM_END_DATE);
    }

    /** Reads the rules for one visa type, since each case here describes a single reader. */
    private Set<ProfileField> fieldsFor(String visaType, String title, String content) {
        return rules.affectedFieldsByVisaType(Collections.singletonList(visaType), title, content)
                .get(PolicyImpactRules.normalizeVisaType(visaType));
    }
}
