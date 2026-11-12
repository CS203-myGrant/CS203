package mygrant.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import mygrant.ai.OpenAiGenerationService;
import mygrant.impact.ImpactJudgeService;
import mygrant.impact.ImpactUrgency;
import mygrant.impact.JudgeEvaluation;
import mygrant.impact.ProfileImpactAssessmentService;
import mygrant.policies.PolicyAssessmentInputs;
import mygrant.policies.PolicyDocument;
import mygrant.policies.SourceType;
import mygrant.user.ProfileFieldConsent;
import mygrant.user.ProfileFieldConsentRepository;
import mygrant.user.ProfileFieldDefinition;
import mygrant.user.User;
import mygrant.user.UserProfile;
import mygrant.user.UserProfileRepository;
import mygrant.user.UserRepository;

class DemoScenarioTests {
    @TempDir Path temporary;

    @TestFactory
    List<DynamicTest> offlinePersonaScenarios() {
        return DemoScenarioCatalog.SCENARIOS.stream().map(scenario -> DynamicTest.dynamicTest(
                scenario.name(), () -> {
                    var fixture = DemoDataRunner.FIXTURES.stream()
                            .filter(item -> item.number().equals(scenario.documentNumber())).findFirst().orElseThrow();
                    String content = new String(getClass().getResourceAsStream(
                            "/demo-policies/" + fixture.file()).readAllBytes(), StandardCharsets.UTF_8);
                    assertThat(content).containsIgnoringCase(fixture.number().equals("2025-23570")
                            ? "Proclamation 10998" : fixture.number().equals("2025-16554")
                            ? "admission" : "Proclamation 10973");
                    var request = DemoDataRunner.requestFor(fixture, content);
                    PolicyDocument policy = new PolicyDocument(fixture.number(), fixture.title(), fixture.agency(),
                            fixture.visas(), fixture.status(), SourceType.PRIMARY, fixture.published(),
                            fixture.effective(), fixture.url(), content, "saved-fixture",
                            request.affectedCountries(), request.restrictionCategories(),
                            request.sourceReferences(), request.countryRestrictions(),
                            request.extractionReviewRequired(), request.specialDocumentHolderRule());

                    User user = new User();
                    user.setId(10L);
                    user.setEmail(scenario.email());
                    UserProfile profile = UserProfile.empty(user.getId());
                    profile.setVisaType(scenario.email().contains("employee") ? "H-1B" : "F-1");
                    profile.setCountryOfCitizenship(scenario.email().contains("student.b") ? "Iran"
                            : scenario.email().contains("student.c") ? "Somalia" : "Singapore");
                    if (scenario.email().contains("student.b") || scenario.email().contains("student.c")) {
                        profile.setUpcomingTravelDate(LocalDate.of(2026, 12, 20));
                    }
                    UserRepository users = mock(UserRepository.class);
                    UserProfileRepository profiles = mock(UserProfileRepository.class);
                    ProfileFieldConsentRepository consents = mock(ProfileFieldConsentRepository.class);
                    OpenAiGenerationService generator = mock(OpenAiGenerationService.class);
                    ImpactJudgeService judge = mock(ImpactJudgeService.class);
                    when(users.findByEmail(scenario.email())).thenReturn(Optional.of(user));
                    when(profiles.findById(10L)).thenReturn(Optional.of(profile));
                    when(consents.findByUserId(10L)).thenReturn(List.of(
                            new ProfileFieldConsent(10L, ProfileFieldDefinition.VISA_TYPE, java.time.Instant.EPOCH),
                            new ProfileFieldConsent(10L, ProfileFieldDefinition.COUNTRY_OF_CITIZENSHIP, java.time.Instant.EPOCH),
                            new ProfileFieldConsent(10L, ProfileFieldDefinition.UPCOMING_TRAVEL_DATE, java.time.Instant.EPOCH)));
                    when(generator.model()).thenReturn("recorded-demo-stub");
                    // This fixed recording checks application wiring; it does not measure model accuracy.
                    String response = new ObjectMapper().writeValueAsString(Map.of(
                            "urgency", scenario.expected().name(), "confidence", 70,
                            "summary", "Recorded offline assessment", "reasons", List.of("Review source"),
                            "recommendedActions", List.of("Read official document"),
                            "missingInformation", List.of(), "requiresHumanReview", false));
                    when(generator.generateJson(anyString(), anyString(), any(), eq("profile_impact_assessment")))
                            .thenReturn(response);
                    when(judge.evaluate(anyString(), any())).thenReturn(
                            new JudgeEvaluation(JudgeEvaluation.Verdict.YES, "Fixture supported", "stub-judge"));

                    var service = new ProfileImpactAssessmentService(new ObjectMapper(), users, profiles,
                            consents, judge, generator);
                    var result = service.assess(scenario.email(), policy, PolicyAssessmentInputs.document(policy),
                            PolicyAssessmentInputs.classification(policy));
                    assertThat(result.urgency()).isEqualTo(scenario.expected());
                    // CS203-153: HIGH no longer forces review; only the model's flag or the judge does,
                    // and this recording sets neither, so every persona's result is publishable.
                    assertThat(result.requiresHumanReview()).isFalse();
                    ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
                    verify(generator).generateJson(anyString(), prompt.capture(), any(), eq("profile_impact_assessment"));
                    assertThat(prompt.getValue()).contains("visaType: " + profile.getVisaType(),
                            "countryOfCitizenship: " + profile.getCountryOfCitizenship(),
                            "documentNumber: " + fixture.number(), fixture.title());
                    if (fixture.number().equals("2025-23570")) {
                        assertThat(prompt.getValue()).contains("country=IR, scope=FULL",
                                "country=SO, scope=FULL", "section-2");
                    }
                    if (profile.getUpcomingTravelDate() != null) {
                        assertThat(prompt.getValue()).contains("upcomingTravelDate: 2026-12-20");
                    }
                })).toList();
    }

    @Test
    void seedCannotRunWithProductionProfile() {
        assertThatThrownBy(() -> DemoDataRunner.assertAllowedProfiles(Set.of("prod", "dev")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DemoDataRunner.assertAllowedProfiles(Set.of("production", "staging")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DemoDataRunner.assertAllowedProfiles(Set.of("default")))
                .isInstanceOf(IllegalStateException.class);
        DemoDataRunner.assertAllowedProfiles(Set.of("dev"));
        DemoDataRunner.assertAllowedProfiles(Set.of("staging"));
    }

    @Test
    void reportIncludesRatesAndHistoryGraphic() throws Exception {
        DemoEvaluationRunner reports = new DemoEvaluationRunner(null, null, temporary.toString(), "key", "key");
        reports.writeReport(List.of(
                new DemoEvaluationRunner.Row(java.time.Instant.parse("2026-11-09T00:00:00Z"),
                        "Student A", "2025-16554", ImpactUrgency.LOW, ImpactUrgency.LOW, false, "YES", "stub"),
                new DemoEvaluationRunner.Row(java.time.Instant.parse("2026-11-09T00:00:00Z"),
                        "Student B", "2025-23570", ImpactUrgency.HIGH, null, true, "NO", "stub"),
                new DemoEvaluationRunner.Row(java.time.Instant.parse("2026-11-09T00:00:00Z"),
                        "Student C", "2025-23570", ImpactUrgency.HIGH, ImpactUrgency.MEDIUM, true, "YES", "stub"),
                new DemoEvaluationRunner.Row(java.time.Instant.parse("2026-11-09T00:00:00Z"),
                        "Employer", "2025-18601", ImpactUrgency.MEDIUM, ImpactUrgency.MEDIUM, true, "NO", null)));
        assertThat(Files.readString(temporary.resolve("latest.md")))
                .contains("Agreement: 1/2 available results", "unavailable: 2/4", "review required: 3/4");
        assertThat(Files.readString(temporary.resolve("history.svg")))
                .contains("Live evaluation history", "50% (1/2)", "75% (3/4)");
        assertThat(Files.readAllLines(temporary.resolve("history.csv"))).hasSize(5);
    }
}
