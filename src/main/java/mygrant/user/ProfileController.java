package mygrant.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import mygrant.user.dto.ProfileResponse;
import mygrant.user.dto.UpdateProfileRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profiles/me")
@Tag(name = "Profile")
public class ProfileController {

    private final UserProfileService userProfileService;

    public ProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @GetMapping
    @Operation(summary = "Get the signed-in user's profile",
            description = "Works before onboarding is finished.")
    public ResponseEntity<ProfileResponse> getCurrentProfile(
            Authentication authentication) {
        return ResponseEntity.ok(
                userProfileService.getProfile(authentication.getName())
        );
    }

    @PutMapping
    @Operation(summary = "Update the signed-in user's profile",
            description = "Fields that are left out or null keep their saved value. A valid save finishes "
                    + "onboarding. Works before onboarding is finished.")
    @ApiResponse(responseCode = "200", description = "The saved profile")
    @ApiResponse(responseCode = "400", description = "A field is invalid, for example only some of the Visa "
            + "Bulletin fields are given")
    public ResponseEntity<ProfileResponse> updateCurrentProfile(
            Authentication authentication,
            @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(
                userProfileService.updateProfile(authentication.getName(), request)
        );
    }
}