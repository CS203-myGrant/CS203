package mygrant.demo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import mygrant.ingestion.PolicyIngestionService;
import mygrant.ingestion.dto.IngestPolicyRequest;
import mygrant.manager.ManagerMembership;
import mygrant.manager.ManagerMembershipRepository;
import mygrant.policies.PolicyStatus;
import mygrant.policies.PolicyDocumentRepository;
import mygrant.policies.PolicyCountryRestriction;
import mygrant.policies.RestrictionCategory;
import mygrant.policies.RestrictionScope;
import mygrant.policies.SourceType;
import mygrant.user.ProfileFieldConsent;
import mygrant.user.ProfileFieldConsentRepository;
import mygrant.user.ProfileFieldDefinition;
import mygrant.user.User;
import mygrant.user.UserProfile;
import mygrant.user.UserProfileRepository;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

/** Explicit, idempotent demo seed; absent from production application profiles. */
@Component
@Profile({"dev", "staging"})
@ConditionalOnProperty(name = "mygrant.demo.enabled", havingValue = "true")
public class DemoDataRunner implements ApplicationRunner {
    private final Environment environment;
    private final UserRepository users;
    private final UserProfileRepository profiles;
    private final ProfileFieldConsentRepository consents;
    private final ManagerMembershipRepository memberships;
    private final BCryptPasswordEncoder encoder;
    private final PolicyIngestionService ingestion;
    private final PolicyDocumentRepository policyDocuments;
    private final DemoEvaluationRunner evaluation;
    private final String applicantPassword;
    private final String managerPassword;
    private final String adminPassword;
    private final String openAiKey;
    private final boolean evaluate;

    public DemoDataRunner(Environment environment, UserRepository users, UserProfileRepository profiles,
            ProfileFieldConsentRepository consents, ManagerMembershipRepository memberships,
            BCryptPasswordEncoder encoder, PolicyIngestionService ingestion,
            PolicyDocumentRepository policyDocuments, DemoEvaluationRunner evaluation,
            @Value("${mygrant.demo.applicant-password:}") String applicantPassword,
            @Value("${mygrant.demo.manager-password:}") String managerPassword,
            @Value("${mygrant.demo.admin-password:}") String adminPassword,
            @Value("${openai.api-key:}") String openAiKey,
            @Value("${mygrant.demo.evaluate:false}") boolean evaluate) {
        this.environment = environment;
        this.users = users;
        this.profiles = profiles;
        this.consents = consents;
        this.memberships = memberships;
        this.encoder = encoder;
        this.ingestion = ingestion;
        this.policyDocuments = policyDocuments;
        this.evaluation = evaluation;
        this.applicantPassword = applicantPassword;
        this.managerPassword = managerPassword;
        this.adminPassword = adminPassword;
        this.openAiKey = openAiKey;
        this.evaluate = evaluate;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        assertAllowedProfiles(Set.of(environment.getActiveProfiles()));
        if (applicantPassword.isBlank() || managerPassword.isBlank() || adminPassword.isBlank()) {
            throw new IllegalStateException("Set DEMO_APPLICANT_PASSWORD, DEMO_MANAGER_PASSWORD and DEMO_ADMIN_PASSWORD");
        }
        if (openAiKey.isBlank() || "not-configured".equals(openAiKey)) {
            throw new IllegalStateException("OPENAI_API_KEY is required to embed the saved demo policies");
        }

        applicant("Student A", "demo.student.a@example.test", "F-1", "Singapore", null);
        applicant("Student B", "demo.student.b@example.test", "F-1", "Iran", LocalDate.of(2026, 12, 15));
        applicant("Student C", "demo.student.c@example.test", "F-1", "Somalia", LocalDate.of(2026, 12, 20));
        User employee = applicant("Linked employee", "demo.employee@example.test", "H-1B", "India",
                LocalDate.of(2026, 12, 20));
        User secondEmployee = applicant("Second linked employee", "demo.employee.two@example.test", "H-1B",
                "Singapore", null);
        User manager = account("Demo employer manager", "demo.manager@example.test", managerPassword,
                UserRole.MANAGER, null);
        account("Demo administrator", "demo.admin@example.test", adminPassword, UserRole.ADMIN, null);
        if (memberships.findByManagerIdAndUserId(manager.getId(), employee.getId()).isEmpty()) {
            memberships.save(new ManagerMembership(manager.getId(), employee.getId()));
        }
        if (memberships.findByManagerIdAndUserId(manager.getId(), secondEmployee.getId()).isEmpty()) {
            memberships.save(new ManagerMembership(manager.getId(), secondEmployee.getId()));
        }

        // Reuse the normal deduplication, rules and full-text embedding pipeline.
        for (PolicyFixture fixture : FIXTURES) {
            if (policyDocuments.findByExternalId(fixture.number()).isPresent()) {
                continue;
            }
            String content = new String(new ClassPathResource("demo-policies/" + fixture.file())
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            ingestion.ingest(requestFor(fixture, content));
        }
        if (evaluate) {
            evaluation.run();
        }
    }

    static void assertAllowedProfiles(Set<String> active) {
        if (active.contains("prod") || active.contains("production")
                || (!active.contains("dev") && !active.contains("staging"))) {
            throw new IllegalStateException("Demo seed requires dev or staging and forbids production");
        }
    }

    static IngestPolicyRequest requestFor(PolicyFixture fixture, String content) {
        if (!"2025-23570".equals(fixture.number())) {
            return new IngestPolicyRequest(fixture.number(), fixture.title(), fixture.agency(),
                    fixture.visas(), fixture.status(), SourceType.PRIMARY, fixture.published(),
                    fixture.effective(), fixture.url(), content);
        }
        // The current 12,000-character assessment excerpt ends before the operative country
        // list. Preserve the two demo countries as explicit, source-linked rules. This is
        // intentionally a partial extraction and is marked for human review.
        String section = fixture.url() + "#section-2";
        String exemption = "See section 6: scope depends on location and a valid visa on the effective date; "
                + "categorical exceptions and case-by-case waivers also apply.";
        return new IngestPolicyRequest(fixture.number(), fixture.title(), fixture.agency(),
                fixture.visas(), fixture.status(), SourceType.PRIMARY, fixture.published(),
                fixture.effective(), fixture.url(), content, Set.of("IR", "SO"),
                Set.of(RestrictionCategory.ENTRY_RESTRICTION, RestrictionCategory.TRAVEL_RESTRICTION),
                List.of(section), List.of(
                        new PolicyCountryRestriction("IR", RestrictionScope.FULL, section, exemption),
                        new PolicyCountryRestriction("SO", RestrictionScope.FULL, section, exemption)),
                true, true);
    }

    private User applicant(String name, String email, String visa, String citizenship, LocalDate travel) {
        User user = account(name, email, applicantPassword, UserRole.APPLICANT, visa);
        user.setCurrentLocation("US");
        user.setUpcomingTravelDate(travel);
        user.setAcademicLevel("Master's");
        user.setProgramEndDate(LocalDate.of(2027, 5, 15));
        users.save(user);

        UserProfile profile = profiles.findById(user.getId()).orElseGet(() -> UserProfile.fromUser(user));
        profile.setVisaType(visa);
        profile.setCountryOfCitizenship(citizenship);
        profile.setCurrentLocation("US");
        profile.setUpcomingTravelDate(travel);
        profile.setAcademicLevel("Master's");
        profile.setProgramEndDate(LocalDate.of(2027, 5, 15));
        profiles.save(profile);
        Set<ProfileFieldDefinition> existing = consents.findByUserId(user.getId()).stream()
                .map(ProfileFieldConsent::getFieldName).collect(java.util.stream.Collectors.toSet());
        for (ProfileFieldDefinition field : List.of(ProfileFieldDefinition.VISA_TYPE,
                ProfileFieldDefinition.COUNTRY_OF_CITIZENSHIP, ProfileFieldDefinition.CURRENT_LOCATION,
                ProfileFieldDefinition.UPCOMING_TRAVEL_DATE, ProfileFieldDefinition.ACADEMIC_LEVEL,
                ProfileFieldDefinition.PROGRAM_END_DATE)) {
            if (!existing.contains(field)) {
                consents.save(new ProfileFieldConsent(user.getId(), field, Instant.now()));
            }
        }
        return user;
    }

    private User account(String name, String email, String password, UserRole role, String visa) {
        User user = users.findByEmail(email).orElseGet(User::new);
        if (user.getEmail() == null) {
            user.setEmail(email);
            user.setPasswordHash(encoder.encode(password));
        }
        user.setFullName(name);
        user.setRole(role);
        user.setVisaType(visa);
        user.setActive(true);
        user.setProfileComplete(true);
        return users.save(user);
    }

    record PolicyFixture(String file, String number, String title, String agency, String visas,
            PolicyStatus status, LocalDate published, LocalDate effective, String url) {}

    static final List<PolicyFixture> FIXTURES = List.of(
            new PolicyFixture("f1-proposed-rule.txt", "2025-16554", "Fixed Time Period of Admission and Extensions of Stay for F, J, and I Nonimmigrants",
                    "DHS", "F-1,J-1", PolicyStatus.PROPOSED, LocalDate.of(2025, 8, 28), null,
                    "https://www.federalregister.gov/d/2025-16554"),
            new PolicyFixture("june-2025-proclamation.txt", "2025-10669", "Proclamation 10949: Restricting Entry of Foreign Nationals",
                    "President", "F-1,J-1,H-1B", PolicyStatus.FINAL, LocalDate.of(2025, 6, 10),
                    LocalDate.of(2025, 6, 9), "https://www.federalregister.gov/d/2025-10669"),
            new PolicyFixture("december-2025-proclamation.txt", "2025-23570", "Proclamation 10998: Restricting and Limiting Entry of Foreign Nationals",
                    "President", "F-1,J-1,H-1B", PolicyStatus.FINAL, LocalDate.of(2025, 12, 19),
                    LocalDate.of(2026, 1, 1), "https://www.federalregister.gov/d/2025-23570"),
            new PolicyFixture("h1b-september-2025-proclamation.txt", "2025-18601", "Proclamation 10973: Restriction on Entry of Certain Nonimmigrant Workers",
                    "President", "H-1B", PolicyStatus.FINAL, LocalDate.of(2025, 9, 24),
                    LocalDate.of(2025, 9, 21), "https://www.federalregister.gov/d/2025-18601"));
}
