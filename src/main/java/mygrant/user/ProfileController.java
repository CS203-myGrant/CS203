package mygrant.user;

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
public class ProfileController {

    private final UserProfileService userProfileService;

    public ProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @GetMapping
    public ResponseEntity<ProfileResponse> getCurrentProfile(
            Authentication authentication) {
        return ResponseEntity.ok(
                userProfileService.getProfile(authentication.getName())
        );
    }

    @PutMapping
    public ResponseEntity<ProfileResponse> updateCurrentProfile(
            Authentication authentication,
            @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(
                userProfileService.updateProfile(authentication.getName(), request)
        );
    }
}