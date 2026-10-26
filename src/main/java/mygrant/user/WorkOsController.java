package mygrant.user;

import java.net.URI;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import mygrant.auth.JwtService;
import mygrant.workos.WorkOsService;

/** Browser redirects for Google sign-in through WorkOS; errors travel in the URL fragment, not as API errors. */
@RestController
@Tag(name = "Authentication")
public class WorkOsController {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkOsController.class);

    private final WorkOsService workOsService;
    private final JwtService jwtService;
    private final UserService userService;

    public WorkOsController(WorkOsService workOsService, JwtService jwtService, UserService userService) {
        this.workOsService = workOsService;
        this.jwtService = jwtService;
        this.userService = userService;
    }

    @GetMapping("/api/v1/auth/workos/login")
    @SecurityRequirements
    @Operation(summary = "Start Google sign-in through WorkOS",
            description = "A browser navigation, not an API call. Redirects to WorkOS, or back to the app "
                    + "with `#workosError=...` when WorkOS is not configured.")
    @ApiResponse(responseCode = "302", description = "Redirect to WorkOS, or back to the app with an error",
            content = @Content)
    public ResponseEntity<Void> login() {
        try {
            URI location = workOsService.authorizationUri(jwtService.generateStateToken());
            return ResponseEntity.status(302).header(HttpHeaders.LOCATION, location.toString()).build();
        } catch (RuntimeException exception) {
            return redirectWithError("WorkOS sign-in is not configured yet.");
        }
    }

    @GetMapping("/api/v1/auth/workos/callback")
    @SecurityRequirements
    @Operation(summary = "Finish Google sign-in",
            description = "WorkOS sends the browser here. Redirects to the app with the tokens in the URL "
                    + "fragment (`#workosToken=...&workosRefreshToken=...`), or with `#workosError=...`.")
    @ApiResponse(responseCode = "302", description = "Redirect back to the app", content = @Content)
    public ResponseEntity<Void> callback(
            @Parameter(description = "Authorization code from WorkOS") @RequestParam(required = false) String code,
            @Parameter(description = "State token issued by the login redirect") @RequestParam(required = false) String state,
            @Parameter(description = "Set by WorkOS when sign-in failed") @RequestParam(required = false) String error) {
        if (error != null || code == null || state == null || !jwtService.isValidStateToken(state)) {
            return redirectWithError("WorkOS sign-in was cancelled or could not be verified.");
        }

        try {
            var login = userService.loginWorkOsUser(workOsService.authenticate(code));
            URI location = URI.create("/#workosToken=" + login.getToken()
                    + "&workosRefreshToken=" + login.getRefreshToken());
            return ResponseEntity.status(302).header(HttpHeaders.LOCATION, location.toString()).build();
        } catch (RuntimeException exception) {
            LOGGER.warn("WorkOS callback could not complete", exception);
            return redirectWithError("WorkOS sign-in could not be completed.");
        }
    }

    private ResponseEntity<Void> redirectWithError(String message) {
        URI location = UriComponentsBuilder.fromPath("/")
                .fragment("workosError=" + message)
                .build().encode().toUri();
        return ResponseEntity.status(302).header(HttpHeaders.LOCATION, location.toString()).build();
    }
}
