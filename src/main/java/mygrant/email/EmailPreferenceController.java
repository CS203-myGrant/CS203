package mygrant.email;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import mygrant.common.ApiError;
import mygrant.email.dto.EmailPreferencesRequest;
import mygrant.email.dto.EmailPreferencesResponse;

/** The signed-in user's alert email settings. */
@RestController
@RequestMapping("/api/v1/profiles/me/email-preferences")
@Tag(name = "Email alerts")
public class EmailPreferenceController {

    private final EmailPreferenceService preferenceService;

    public EmailPreferenceController(EmailPreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    @GetMapping
    @Operation(summary = "Get alert email settings",
            description = "Returns the defaults (IMMEDIATE for critical, DIGEST for other) until the user saves a choice.")
    public EmailPreferencesResponse get(Authentication authentication) {
        return preferenceService.get(authentication.getName());
    }

    @PutMapping
    @Operation(summary = "Replace alert email settings")
    @ApiResponse(responseCode = "200", description = "Settings saved")
    @ApiResponse(responseCode = "400", description = "A mode is missing or not IMMEDIATE, DIGEST or OFF",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public EmailPreferencesResponse update(Authentication authentication,
            @Valid @RequestBody EmailPreferencesRequest request) {
        return preferenceService.update(authentication.getName(), request);
    }
}
