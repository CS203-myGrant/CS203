package mygrant.user;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;

import jakarta.validation.Valid;
import mygrant.user.dto.LoginResponse;
import mygrant.user.dto.UserCreation;
import mygrant.user.dto.UserLogin;

@CrossOrigin(origins = "*")
@RestController
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
    public ResponseEntity<?> receiveRequest(@Valid @RequestBody UserCreation userCreation) {
        LoginResponse result = userService.createUser(userCreation);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/v1/auth/login")
    public ResponseEntity<?> login(@Valid @RequestBody UserLogin userLogin) {
        LoginResponse response = userService.loginUser(userLogin);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/v1/users/me")
    public ResponseEntity<LoginResponse> getCurrentUser(
            Authentication authentication) {
        return userRepository.findByEmail(authentication.getName())
                .map(user -> ResponseEntity.ok(new LoginResponse(user)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

}
