package mygrant.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import mygrant.ai.OpenAiGenerationService;
import mygrant.ingestion.PolicyIngestionService;
import mygrant.ingestion.dto.IngestPolicyRequest;
import mygrant.ingestion.federalregister.FederalRegisterClient;
import mygrant.ingestion.federalregister.FederalRegisterDocument;
import mygrant.ingestion.federalregister.FederalRegisterImportRequest;
import mygrant.ingestion.federalregister.FederalRegisterIngestionService;
import mygrant.policies.PolicyDocument;
import mygrant.policies.PolicyDocumentRepository;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;
import mygrant.support.PgvectorIntegrationTestBase;
import mygrant.user.ProfileFieldConsent;
import mygrant.user.ProfileFieldConsentRepository;
import mygrant.user.ProfileFieldDefinition;
import mygrant.user.User;
import mygrant.user.UserProfile;
import mygrant.user.UserProfileRepository;
import mygrant.user.UserRepository;

/** Real commits, real async executor and real uniqueness constraints; no external AI or embedding calls. */
@SpringBootTest(properties = {
        "mygrant.impact.assessments-enabled=true",
        "mygrant.impact.review-confidence-threshold=75",
        "jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@Import(IngestionAssessmentIntegrationTest.ProviderConfiguration.class)
class IngestionAssessmentIntegrationTest extends PgvectorIntegrationTestBase {
    @Autowired PolicyIngestionService ingestion;
    @Autowired FederalRegisterIngestionService federalRegister;
    @Autowired IngestionAssessmentService worker;
    @Autowired ImpactAssessmentRepository results;
    @Autowired UserRepository users;
    @Autowired UserProfileRepository profiles;
    @Autowired ProfileFieldConsentRepository consents;
    @Autowired PolicyDocumentRepository policies;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("assessmentTestGenerator") OpenAiGenerationService generator;
    @MockBean VectorStore vectors;
    @MockBean ImpactJudgeService judge;
    @MockBean FederalRegisterClient client;

    private final List<Long> createdUsers = new ArrayList<>();
    private final List<Long> createdPolicies = new ArrayList<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {
        @Bean
        @Primary
        OpenAiGenerationService assessmentTestGenerator() {
            var generator = mock(OpenAiGenerationService.class);
            when(generator.model()).thenReturn("test-model");
            return generator;
        }
    }

    @BeforeEach
    void providerDefaults() {
        reset(generator);
        when(generator.model()).thenReturn("test-model");
        when(generator.generateJson(anyString(), anyString(), any(), anyString()))
                .thenReturn(candidate(85, false, "Profile-specific result"));
        when(judge.evaluate(anyString(), any())).thenReturn(
                new JudgeEvaluation(JudgeEvaluation.Verdict.YES, "Supported", "test-judge"));
    }

    @AfterEach
    void cleanUp() {
        // Each async test waits for its final row before cleanup; overlapping workers are joined explicitly.
        for (Long policyId : createdPolicies) policies.deleteById(policyId);
        for (Long userId : createdUsers) users.deleteById(userId);
        createdPolicies.clear();
        createdUsers.clear();
    }

    @Test
    void manualImportReturnsWhileGenerationWaitsAndSavesDistinctAnonymisedProfileResults() throws Exception {
        UserProfile early = profile("F-1", "2026-12-01", 3);
        UserProfile late = profile(" f-1 ", "2028-12-01", 1);
        UserProfile nonMatching = profile("J-1", "2027-12-01", 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> prompts = new CopyOnWriteArrayList<>();
        when(generator.generateJson(anyString(), anyString(), any(), anyString())).thenAnswer(call -> {
            String prompt = call.getArgument(1);
            prompts.add(prompt);
            started.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
            return candidate(85, false, prompt.contains("2026-12-01") ? "Earlier expiry" : "Later expiry");
        });
        var request = request("F-1");
        Long policyId;
        try {
            var response = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> ingestion.ingest(request));
            policyId = response.policyId();
            createdPolicies.add(policyId);
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(policies.existsById(policyId)).isTrue(); // The worker sees a committed policy.
            assertThat(rows(policyId)).isEmpty(); // The HTTP/service path did not wait for the provider.
            early.setVersion(4);
            early.setVisaExpiryDate(LocalDate.of(2030, 1, 1));
            profiles.saveAndFlush(early);
        } finally {
            release.countDown();
        }
        awaitRows(policyId, 2);
        var savedEarly = results.findByUserIdAndPolicyIdAndProfileVersion(early.getUserId(), policyId, 3).orElseThrow();
        var savedLate = results.findByUserIdAndPolicyIdAndProfileVersion(late.getUserId(), policyId, 1).orElseThrow();
        assertThat(savedEarly.getSummary()).isEqualTo("Earlier expiry");
        assertThat(savedLate.getSummary()).isEqualTo("Later expiry");
        assertThat(savedEarly.getProvider()).isEqualTo("OpenAI");
        assertThat(savedEarly.getModel()).isEqualTo("test-model");
        assertThat(results.findByUserIdAndPolicyIdAndProfileVersion(nonMatching.getUserId(), policyId, 1)).isEmpty();
        assertThat(prompts).hasSize(2).allSatisfy(prompt -> {
            assertThat(prompt).doesNotContain("Secret Applicant", "@example.org", "Secret Employer", "userId:", "employer:");
            assertThat(prompt).contains("employerProvided: true", "visaExpiryDate:");
        });
        // Unchanged re-ingestion does not assess the newly edited version or duplicate the original rows.
        assertThat(ingestion.ingest(request).alreadyIndexed()).isTrue();
        assertThat(rows(policyId)).hasSize(2);
        verify(generator, times(2)).generateJson(anyString(), anyString(), any(), anyString());
    }

    @Test
    void federalRegisterImportAssessesMatchingUsersEvenWhenImporterDoesNotMatchAndLegacyFlagIsFalse() {
        profile("F-1", "2026-12-01", 1);
        profile("F-1", "2028-12-01", 2);
        var importer = profile("J-1", "2027-12-01", 1);
        String number = "fr-" + UUID.randomUUID();
        var document = new FederalRegisterDocument(number, "F-1 student rule", "Rule", LocalDate.now(), null,
                "https://example.org/" + number, "https://example.org/raw/" + number, List.of());
        when(client.getDocument(number)).thenReturn(document);
        when(client.downloadFullText(document.rawTextUrl())).thenReturn("F-1 students must check their expiry dates.");
        String importerEmail = users.findById(importer.getUserId()).orElseThrow().getEmail();
        var response = federalRegister.ingest(new FederalRegisterImportRequest(number, importerEmail, false));
        Long policyId = response.ingestion().policyId();
        createdPolicies.add(policyId);
        assertThat(response.impactAssessment()).isNull();
        awaitRows(policyId, 2);
        assertThat(rows(policyId)).noneMatch(row -> row.getUserId().equals(importer.getUserId()));
    }

    @Test
    void providerFailureIsStoredWithoutRollingBackPolicyOrStoppingOtherUsers() {
        var failed = profile("F-1", "2026-12-01", 1);
        var successful = profile("F-1", "2028-12-01", 1);
        when(generator.generateJson(anyString(), anyString(), any(), anyString())).thenAnswer(call -> {
            if (call.<String>getArgument(1).contains("2026-12-01")) {
                throw new IllegalStateException("Sensitive provider diagnostic");
            }
            return candidate(85, false, "Successful result");
        });
        Long policyId = ingest("F-1");
        awaitRows(policyId, 2);
        var unavailable = results.findByUserIdAndPolicyIdAndProfileVersion(failed.getUserId(), policyId, 1).orElseThrow();
        assertThat(unavailable.getStatus()).isEqualTo(AssessmentStatus.UNAVAILABLE);
        assertThat(unavailable.getUrgency()).isNull();
        assertThat(unavailable.getConfidence()).isNull();
        assertThat(unavailable.getProvider()).isEqualTo("OpenAI");
        assertThat(unavailable.getModel()).isEqualTo("test-model");
        assertThat(unavailable.getSummary()).doesNotContain("Sensitive provider diagnostic");
        assertThat(results.findVisibleByUserId(failed.getUserId())).isEmpty();
        assertThat(results.findVisibleByUserId(successful.getUserId())).hasSize(1);
        assertThat(policies.existsById(policyId)).isTrue();
        // Ordinary duplicate processing preserves the retryable failure instead of making another row.
        worker.assessMatchingProfiles(policyId);
        assertThat(rows(policyId)).hasSize(2);
        verify(generator, times(2)).generateJson(anyString(), anyString(), any(), anyString());
    }

    @ParameterizedTest
    @CsvSource({"74,false,true", "75,false,false", "76,false,false", "95,true,true"})
    void reviewThresholdAndModelFlagGateVisibility(int confidence, boolean modelReview, boolean expectedReview) {
        var owner = profile("F-1", "2028-12-01", 1);
        var stranger = profile("J-1", "2028-12-01", 1);
        when(generator.generateJson(anyString(), anyString(), any(), anyString()))
                .thenReturn(candidate(confidence, modelReview, "Review rule result"));
        Long policyId = ingest("F-1");
        awaitRows(policyId, 1);
        var saved = rows(policyId).getFirst();
        assertThat(saved.isRequiresReview()).isEqualTo(expectedReview);
        assertThat(saved.getReviewStatus()).isEqualTo(expectedReview
                ? AssessmentReviewStatus.PENDING_REVIEW : AssessmentReviewStatus.NOT_REQUIRED);
        assertThat(results.findVisibleByUserId(owner.getUserId())).hasSize(expectedReview ? 0 : 1);
        assertThat(results.findVisibleByUserId(stranger.getUserId())).isEmpty();
        if (expectedReview) {
            jdbc.update("UPDATE impact_assessments SET review_status = 'APPROVED' WHERE id = ?", saved.getId());
            assertThat(results.findVisibleByUserId(owner.getUserId())).extracting(ImpactAssessment::getId)
                    .containsExactly(saved.getId());
            jdbc.update("UPDATE impact_assessments SET review_status = 'REJECTED' WHERE id = ?", saved.getId());
            assertThat(results.findVisibleByUserId(owner.getUserId())).isEmpty();
        }
    }

    @Test
    void manualImportMatchesEveryVisaTagAndHoldsAnUnverifiedResultForReview() {
        var student = profile("F-1", "2028-12-01", 1);
        var exchangeVisitor = profile("J-1", "2028-12-01", 1);
        var workerProfile = profile("H-1B", "2028-12-01", 1);
        when(judge.evaluate(anyString(), any())).thenReturn(
                new JudgeEvaluation(JudgeEvaluation.Verdict.NO, "Needs review", "test-judge"));
        Long policyId = ingest("F-1,J-1");
        awaitRows(policyId, 2);
        assertThat(rows(policyId)).extracting(ImpactAssessment::getUserId)
                .containsExactlyInAnyOrder(student.getUserId(), exchangeVisitor.getUserId());
        assertThat(rows(policyId)).allSatisfy(row -> {
            assertThat(row.isRequiresReview()).isTrue();
            assertThat(row.getReviewStatus()).isEqualTo(AssessmentReviewStatus.PENDING_REVIEW);
        });
        assertThat(results.findVisibleByUserId(student.getUserId())).isEmpty();
        assertThat(results.findVisibleByUserId(exchangeVisitor.getUserId())).isEmpty();
        assertThat(results.findVisibleByUserId(workerProfile.getUserId())).isEmpty();
    }

    @Test
    void rolledBackIngestionDoesNotStartAssessments() {
        profile("F-1", "2028-12-01", 1);
        var request = request("F-1");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            ingestion.ingest(request);
            verify(generator, never()).generateJson(anyString(), anyString(), any(), anyString());
            tx.setRollbackOnly();
        });
        assertThat(policies.findByExternalId(request.externalId())).isEmpty();
        verify(generator, never()).generateJson(anyString(), anyString(), any(), anyString());
    }

    @Test
    void overlappingWorkersCannotCreateDuplicateRows() throws Exception {
        var owner = profile("F-1", "2028-12-01", 1);
        var request = request("F-1");
        // Store a committed policy without publishing an event, so the two workers start together.
        var policy = policies.saveAndFlush(new PolicyDocument(request.externalId(), request.title(), request.agency(),
                request.visaType(), request.status(), request.sourceType(), request.publicationDate(),
                request.effectiveDate(), request.sourceUrl(), request.content(), "0".repeat(64)));
        createdPolicies.add(policy.getId());
        CyclicBarrier bothGenerating = new CyclicBarrier(2);
        when(generator.generateJson(anyString(), anyString(), any(), anyString())).thenAnswer(call -> {
            bothGenerating.await(10, TimeUnit.SECONDS);
            return candidate(85, false, "Concurrent result");
        });
        var first = CompletableFuture.runAsync(() -> worker.assessMatchingProfiles(policy.getId()));
        var second = CompletableFuture.runAsync(() -> worker.assessMatchingProfiles(policy.getId()));
        CompletableFuture.allOf(first, second).get(15, TimeUnit.SECONDS);
        assertThat(rows(policy.getId())).hasSize(1);
        assertThat(results.findVisibleByUserId(owner.getUserId())).hasSize(1);
        verify(generator, times(2)).generateJson(anyString(), anyString(), any(), anyString());
    }

    private UserProfile profile(String visaType, String expiry, int version) {
        User user = users.saveAndFlush(new User("Secret Applicant", UUID.randomUUID() + "@example.org", "unused"));
        createdUsers.add(user.getId());
        UserProfile profile = UserProfile.registrationProfile(user.getId(), visaType, null, null, null, null);
        profile.setVisaExpiryDate(LocalDate.parse(expiry));
        profile.setEmployer("Secret Employer");
        profile.setVersion(version);
        profiles.saveAndFlush(profile);
        for (var field : List.of(ProfileFieldDefinition.VISA_TYPE, ProfileFieldDefinition.VISA_EXPIRY_DATE,
                ProfileFieldDefinition.EMPLOYER)) {
            consents.saveAndFlush(new ProfileFieldConsent(user.getId(), field, Instant.now()));
        }
        return profile;
    }

    private Long ingest(String visas) {
        Long id = ingestion.ingest(request(visas)).policyId();
        createdPolicies.add(id);
        return id;
    }

    private IngestPolicyRequest request(String visas) {
        String externalId = "async-" + UUID.randomUUID();
        return new IngestPolicyRequest(externalId, "Student expiry rule", "DHS", visas, PolicyStatus.FINAL,
                SourceType.PRIMARY, LocalDate.now(), null, "https://example.org/" + externalId,
                "Students must check their visa expiry dates.");
    }

    private List<ImpactAssessment> rows(Long policyId) {
        return results.findAll().stream().filter(row -> row.getPolicyId().equals(policyId)).toList();
    }

    private void awaitRows(Long policyId, int count) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(rows(policyId)).hasSize(count));
    }

    private static String candidate(int confidence, boolean review, String summary) {
        return """
                {"urgency":"MEDIUM","confidence":%d,"summary":"%s",
                 "reasons":["Visa expiry timing"],"recommendedActions":["Check the official rule"],
                 "missingInformation":[],"requiresHumanReview":%s}
                """.formatted(confidence, summary, review);
    }
}
