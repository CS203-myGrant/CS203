package mygrant.testclock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import mygrant.testclock.dto.TestClockRequest;
import mygrant.testclock.dto.TestClockResponse;

/**
 * TEMPORARY: lets testers move "today" to when a policy is published and watch the alert
 * arrive. Absent, so every path is a 404, unless mygrant.test-clock.enabled is true.
 */
@RestController
@ConditionalOnProperty(name = "mygrant.test-clock.enabled", havingValue = "true")
public class TestClockController {

    private final TestClockService testClockService;

    public TestClockController(TestClockService testClockService) {
        this.testClockService = testClockService;
    }

    @GetMapping("/api/v1/test-clock/me")
    public TestClockResponse status(Authentication authentication) {
        return testClockService.status(authentication.getName());
    }

    /** Moves only the caller's clock and re-matches only their inbox. */
    @PutMapping("/api/v1/test-clock/me")
    public TestClockResponse setPersonal(Authentication authentication,
            @Valid @RequestBody TestClockRequest request) {
        return testClockService.setPersonalDate(authentication.getName(), request.date());
    }

    @DeleteMapping("/api/v1/test-clock/me")
    public TestClockResponse resetPersonal(Authentication authentication) {
        return testClockService.setPersonalDate(authentication.getName(), null);
    }

    /**
     * Moves every applicant's clock, overriding their own dates, and re-matches every inbox.
     * Importing recent policies can take a minute: each document is downloaded and embedded.
     */
    @PutMapping("/api/v1/admin/test-clock")
    @PreAuthorize("hasRole('ADMIN')")
    public TestClockResponse setGlobal(Authentication authentication,
            @Valid @RequestBody TestClockRequest request) {
        return testClockService.setGlobalDate(authentication.getName(), request.date(),
                request.importRecentPolicies());
    }

    @DeleteMapping("/api/v1/admin/test-clock")
    @PreAuthorize("hasRole('ADMIN')")
    public TestClockResponse resetGlobal(Authentication authentication) {
        return testClockService.setGlobalDate(authentication.getName(), null, false);
    }
}
