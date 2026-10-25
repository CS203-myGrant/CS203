package mygrant.testclock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import mygrant.testclock.dto.TestClockRequest;
import mygrant.testclock.dto.TestClockResponse;

/**
 * TEMPORARY: lets testers move "today" to when a policy is published and watch the alert
 * arrive. Absent, so every path is a 404, unless mygrant.test-clock.enabled is true.
 */
@RestController
@ConditionalOnProperty(name = "mygrant.test-clock.enabled", havingValue = "true")
@Tag(name = "Test clock")
public class TestClockController {

    private final TestClockService testClockService;

    public TestClockController(TestClockService testClockService) {
        this.testClockService = testClockService;
    }

    @GetMapping("/api/v1/test-clock/me")
    @Operation(summary = "Get the signed-in user's test clock")
    public TestClockResponse status(Authentication authentication) {
        return testClockService.status(authentication.getName());
    }

    /** Moves only the caller's clock and re-matches only their inbox. */
    @PutMapping("/api/v1/test-clock/me")
    @Operation(summary = "Move the signed-in user's date",
            description = "Re-matches only the caller's inbox against the new date.")
    @ApiResponse(responseCode = "200", description = "The clock after the change")
    @ApiResponse(responseCode = "400", description = "The date is missing or invalid")
    public TestClockResponse setPersonal(Authentication authentication,
            @Valid @RequestBody TestClockRequest request) {
        return testClockService.setPersonalDate(authentication.getName(), request.date());
    }

    @DeleteMapping("/api/v1/test-clock/me")
    @Operation(summary = "Return the signed-in user to the shared date")
    public TestClockResponse resetPersonal(Authentication authentication) {
        return testClockService.setPersonalDate(authentication.getName(), null);
    }

    /**
     * Moves every applicant's clock, overriding their own dates, and re-matches every inbox.
     * Importing recent policies can take a minute: each document is downloaded and embedded.
     */
    @PutMapping("/api/v1/admin/test-clock")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Move every applicant's date",
            description = "Overrides their own dates and re-matches every inbox. With `importRecentPolicies`, "
                    + "first imports the Federal Register policies of the 30 days up to the date, which can "
                    + "take a minute.")
    @ApiResponse(responseCode = "200", description = "The clock after the change, with what it imported")
    @ApiResponse(responseCode = "400", description = "The date is missing or invalid")
    public TestClockResponse setGlobal(Authentication authentication,
            @Valid @RequestBody TestClockRequest request) {
        return testClockService.setGlobalDate(authentication.getName(), request.date(),
                request.importRecentPolicies());
    }

    @DeleteMapping("/api/v1/admin/test-clock")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Return every applicant to the real date")
    public TestClockResponse resetGlobal(Authentication authentication) {
        return testClockService.setGlobalDate(authentication.getName(), null, false);
    }
}
