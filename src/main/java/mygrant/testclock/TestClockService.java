package mygrant.testclock;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import mygrant.ingestion.federalregister.FederalRegisterClient;
import mygrant.ingestion.federalregister.FederalRegisterImportRequest;
import mygrant.ingestion.federalregister.FederalRegisterIngestionService;
import mygrant.notifications.NotificationService;
import mygrant.notifications.NotificationService.InboxSync;
import mygrant.policies.PolicyDocumentRepository;
import mygrant.testclock.dto.TestClockResponse;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

/** TEMPORARY: moves the test clock and brings the affected inboxes in line with it. */
@Service
public class TestClockService {

    private static final Logger log = LoggerFactory.getLogger(TestClockService.class);

    // How far back from the new date the admin's import looks, and how many documents it
    // takes at most. Every import downloads the full text and embeds it, so a wide window
    // would keep the admin waiting for minutes.
    static final int IMPORT_WINDOW_DAYS = 30;
    static final int MAX_IMPORTS = 20;

    private final TestClock testClock;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final FederalRegisterClient federalRegisterClient;
    private final FederalRegisterIngestionService federalRegisterIngestionService;
    private final PolicyDocumentRepository policyRepository;

    public TestClockService(TestClock testClock, UserRepository userRepository,
            NotificationService notificationService, FederalRegisterClient federalRegisterClient,
            FederalRegisterIngestionService federalRegisterIngestionService,
            PolicyDocumentRepository policyRepository) {
        this.testClock = testClock;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.federalRegisterClient = federalRegisterClient;
        this.federalRegisterIngestionService = federalRegisterIngestionService;
        this.policyRepository = policyRepository;
    }

    public TestClockResponse status(String email) {
        return response(currentUser(email).getId(), new InboxSync(0, 0), 0, PolicyImport.NONE);
    }

    /** Sets or, with null, clears the signed-in user's own date. */
    public TestClockResponse setPersonalDate(String email, LocalDate date) {
        User user = currentUser(email);
        testClock.setUserDate(user.getId(), date);
        return response(user.getId(), notificationService.syncToTestClock(user), 1, PolicyImport.NONE);
    }

    /**
     * Sets or, with null, clears the date for every applicant, dropping their own dates.
     * With importRecent, Federal Register documents published in the window before the date
     * are imported first, so the date has policies to alert about even when nobody imported
     * any by hand.
     */
    public TestClockResponse setGlobalDate(String email, LocalDate date, boolean importRecent) {
        testClock.setGlobalDate(date);
        PolicyImport policyImport = importRecent
                ? importRecentPolicies(email, date == null ? testClock.realToday() : date)
                : PolicyImport.NONE;

        List<User> applicants = userRepository.findAllByActiveTrueOrderByIdAsc().stream()
                .filter(user -> user.getRole() == UserRole.APPLICANT)
                .toList();
        int added = 0;
        int removed = 0;
        // Each inbox syncs in its own transaction, so one failure leaves the others moved.
        for (User applicant : applicants) {
            InboxSync sync = notificationService.syncToTestClock(applicant);
            added += sync.added();
            removed += sync.removed();
        }
        return response(currentUser(email).getId(), new InboxSync(added, removed), applicants.size(), policyImport);
    }

    // The clock is already set when documents are imported, so each import alerts the
    // applicants it should; the inbox sync that follows finds those alerts and skips them.
    private PolicyImport importRecentPolicies(String email, LocalDate date) {
        List<String> documentNumbers = federalRegisterClient.searchDocumentNumbers(
                date.minusDays(IMPORT_WINDOW_DAYS), date, MAX_IMPORTS);
        int imported = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        for (String documentNumber : documentNumbers) {
            if (policyRepository.findByExternalId(documentNumber).isPresent()) {
                skipped++;
                continue;
            }
            try {
                federalRegisterIngestionService.ingest(new FederalRegisterImportRequest(documentNumber, email, false));
                imported++;
            } catch (IllegalArgumentException notAboutSupportedVisa) {
                // The search matches any mention; the classifier rejects documents that are
                // not about F-1, J-1 or H-1B, such as a navigation rule naming a vessel's crew.
                skipped++;
            } catch (RuntimeException exception) {
                log.warn("Test clock could not import Federal Register document {}", documentNumber, exception);
                failures.add(documentNumber + ": " + exception.getClass().getSimpleName());
            }
        }
        return new PolicyImport(documentNumbers.size(), imported, skipped, failures);
    }

    private record PolicyImport(int found, int imported, int skipped, List<String> failures) {
        static final PolicyImport NONE = new PolicyImport(0, 0, 0, List.of());
    }

    private TestClockResponse response(Long userId, InboxSync sync, int inboxesSynced, PolicyImport policyImport) {
        return new TestClockResponse(testClock.today(userId), testClock.realToday(),
                testClock.userDate(userId), testClock.globalDate(),
                inboxesSynced, sync.added(), sync.removed(),
                policyImport.found(), policyImport.imported(), policyImport.skipped(), policyImport.failures());
    }

    private User currentUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists"));
    }
}
