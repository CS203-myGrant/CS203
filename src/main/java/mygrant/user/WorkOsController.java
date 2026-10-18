package mygrant.user;

import java.net.URI;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import mygrant.auth.JwtService;
import mygrant.workos.WorkOsService;

@RestController
public class WorkOsController {

    private final WorkOsService workOsService;
    private final JwtService jwtService;
    private final UserService userService;

    public WorkOsController(WorkOsService workOsService, JwtService jwtService, UserService userService) {
        this.workOsService = workOsService;
        this.jwtService = jwtService;
        this.userService = userService;
    }

    @GetMapping("/api/v1/auth/workos/login")
    public ResponseEntity<Void> login() {
        try {
            URI location = workOsService.authorizationUri(jwtService.generateStateToken());
            return ResponseEntity.status(302).header(HttpHeaders.LOCATION, location.toString()).build();
        } catch (RuntimeException exception) {
            return redirectWithError("WorkOS sign-in is not configured yet.");
        }
    }

    @GetMapping("/api/v1/auth/workos/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error) {
        if (error != null || code == null || state == null || !jwtService.isValidStateToken(state)) {
            return redirectWithError("WorkOS sign-in was cancelled or could not be verified.");
        }

        try {
            String token = userService.loginWorkOsUser(workOsService.authenticate(code)).getToken();
            URI location = URI.create("/#workosToken=" + token);
            return ResponseEntity.status(302).header(HttpHeaders.LOCATION, location.toString()).build();
        } catch (RuntimeException exception) {
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
