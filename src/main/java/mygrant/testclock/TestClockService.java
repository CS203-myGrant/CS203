package mygrant.testclock;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;

import mygrant.notifications.NotificationService;
import mygrant.notifications.NotificationService.InboxSync;
import mygrant.testclock.dto.TestClockResponse;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

/** TEMPORARY: moves the test clock and brings the affected inboxes in line with it. */
@Service
public class TestClockService {

    private final TestClock testClock;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public TestClockService(TestClock testClock, UserRepository userRepository,
            NotificationService notificationService) {
        this.testClock = testClock;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    public TestClockResponse status(String email) {
        return response(currentUser(email).getId(), new InboxSync(0, 0), 0);
    }

    /** Sets or, with null, clears the signed-in user's own date. */
    public TestClockResponse setPersonalDate(String email, LocalDate date) {
        User user = currentUser(email);
        testClock.setUserDate(user.getId(), date);
        return response(user.getId(), notificationService.syncToTestClock(user), 1);
    }

    /** Sets or, with null, clears the date for every applicant, dropping their own dates. */
    public TestClockResponse setGlobalDate(String email, LocalDate date) {
        testClock.setGlobalDate(date);
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
        return response(currentUser(email).getId(), new InboxSync(added, removed), applicants.size());
    }

    private TestClockResponse response(Long userId, InboxSync sync, int inboxesSynced) {
        return new TestClockResponse(testClock.today(userId), testClock.realToday(),
                testClock.userDate(userId), testClock.globalDate(),
                inboxesSynced, sync.added(), sync.removed());
    }

    private User currentUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists"));
    }
}
