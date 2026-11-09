package mygrant.user;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import mygrant.user.dto.LoginResponse;
import mygrant.user.dto.UserCreation;
import mygrant.user.dto.UserLogin;
import mygrant.user.dto.RefreshTokenRequest;


@RestController
@Tag(name = "Authentication")
public class UserController {

    private final UserService userService;
    private final UserRepository userRepository;

    public UserController(
        UserService userService,
        UserRepository userRepository) {
    this.userService = userService;
    this.userRepository = userRepository;
    }   

    @PostMapping("/api/v1/auth/register")
    @SecurityRequirements
    @Operation(summary = "Register with email and password",
            description = "Creates an applicant account and returns its tokens, like login.")
    @ApiResponse(responseCode = "200", description = "Registered and signed in")
    @ApiResponse(responseCode = "400", description = "A field is missing or invalid, or the account could not be "
            + "created. The message never says whether the email is already registered.")
    public ResponseEntity<LoginResponse> receiveRequest(@Valid @RequestBody UserCreation userCreation) {
        LoginResponse result = userService.createUser(userCreation);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/v1/auth/login")
    @SecurityRequirements
    @Operation(summary = "Sign in with email and password",
            description = "Returns a short-lived access token (15 minutes by default) for the Authorization "
                    + "header, and a refresh token for this device.")
    @ApiResponse(responseCode = "200", description = "Signed in")
    @ApiResponse(responseCode = "400", description = "Email or password is missing or malformed")
    @ApiResponse(responseCode = "401", description = "Invalid email or password")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody UserLogin userLogin) {
        LoginResponse response = userService.loginUser(userLogin);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/v1/auth/refresh")
    @SecurityRequirements
    @Operation(summary = "Exchange a refresh token for new tokens",
            description = "Refresh tokens are single-use: the response carries the replacement.")
    @ApiResponse(responseCode = "200", description = "New access and refresh tokens")
    @ApiResponse(responseCode = "400", description = "The refresh token is missing")
    @ApiResponse(responseCode = "401", description = "The refresh token is invalid, expired, revoked or already used")
    public ResponseEntity<LoginResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(userService.refresh(request.refreshToken()));
    }

    @PostMapping("/api/v1/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Sign out this device",
            description = "Revokes the given refresh token; an unknown token is ignored. The access token "
                    + "stays valid until it expires. Works before onboarding is finished.")
    @ApiResponse(responseCode = "204", description = "Signed out")
    @ApiResponse(responseCode = "400", description = "The refresh token is missing")
    public void logout(
            Authentication authentication,
            @Valid @RequestBody RefreshTokenRequest request) {
        userService.logout(authentication.getName(), request.refreshToken());
    }

    @GetMapping("/api/v1/users/me")
    @Operation(summary = "Get the signed-in user",
            description = "The token fields are empty. Works before onboarding is finished.")
    @ApiResponse(responseCode = "200", description = "The signed-in user")
    @ApiResponse(responseCode = "404", description = "The account no longer exists")
    public ResponseEntity<LoginResponse> getCurrentUser(
            Authentication authentication) {
        return userRepository.findByEmail(authentication.getName())
                .map(user -> ResponseEntity.ok(new LoginResponse(user)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

}
