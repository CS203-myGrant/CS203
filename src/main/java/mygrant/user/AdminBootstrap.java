package mygrant.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdminBootstrap implements CommandLineRunner {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final String email;
    private final String password;

    public AdminBootstrap(
            UserRepository userRepository,
            BCryptPasswordEncoder passwordEncoder,
            @Value("${admin.bootstrap.email:}") String email,
            @Value("${admin.bootstrap.password:}") String password) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.email = email == null ? "" : email.trim().toLowerCase();
        this.password = password == null ? "" : password;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (email.isBlank() && password.isBlank()) {
            return;
        }
        if (email.isBlank() || password.isBlank()) {
            throw new IllegalStateException("ADMIN_EMAIL and ADMIN_PASSWORD must be configured together");
        }

        User user = userRepository.findByEmail(email).orElseGet(User::new);
        if (user.getEmail() == null) {
            user.setFullName("Configured administrator");
            user.setEmail(email);
        }
        if (user.getPasswordHash() == null) {
            user.setPasswordHash(passwordEncoder.encode(password));
        }
        user.setRole(UserRole.ADMIN);
        user.setActive(true);
        user.setProfileComplete(true);
        userRepository.save(user);
    }
}
