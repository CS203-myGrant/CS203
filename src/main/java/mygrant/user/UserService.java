package mygrant.user;

import java.util.Optional;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;

import mygrant.auth.JwtService;
import mygrant.common.InvalidCredentialsException;
import mygrant.common.DuplicateEmailException;
import mygrant.user.dto.LoginResponse;
import mygrant.user.dto.UserCreation;
import mygrant.user.dto.UserLogin;
import mygrant.user.dto.ProfileCompletion;
import mygrant.user.dto.WorkOsUser;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public UserService(
            UserRepository userRepository,
            UserProfileRepository userProfileRepository,
            BCryptPasswordEncoder passwordEncoder,
            JwtService jwtService) {
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public LoginResponse createUser(UserCreation userCreation) {
        String email = userCreation.getUserEmail().trim().toLowerCase();
        String userName = userCreation.getUserName().trim();

        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException();
        }

        User user = new User();
        user.setFullName(userName);
        user.setEmail(email);
        user.setVisaType(userCreation.getVisaType());
        user.setAcademicLevel(userCreation.getAcademicLevel());
        user.setProgramEndDate(userCreation.getProgramEndDate());
        user.setCurrentLocation(userCreation.getCurrentLocation());
        user.setUpcomingTravelDate(userCreation.getUpcomingTravelDate());
        user.setRole(UserRole.APPLICANT);
        user.setPasswordHash(passwordEncoder.encode(userCreation.getUserPassword()));

        User savedUser;
        try {
            // The database unique constraint is still required because two requests
            // can pass existsByEmail at the same time.
            savedUser = userRepository.save(user);
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateEmailException();
        }
        userProfileRepository.save(UserProfile.fromUser(savedUser));
        String token = jwtService.generateToken(savedUser.getEmail());
        
        return new LoginResponse(savedUser, token);
    }

    public LoginResponse loginUser(UserLogin userLogin) {
        Optional<User> optionalUser = userRepository.findByEmail(
                userLogin.getUserEmail().trim().toLowerCase());

        if (optionalUser.isEmpty()) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        User user = optionalUser.get();

        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(userLogin.getUserPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        String token = jwtService.generateToken(user.getEmail());
        return new LoginResponse(user, token);
    }

    public LoginResponse loginWorkOsUser(WorkOsUser workOsUser) {
        String email = workOsUser.email().trim().toLowerCase();
        User user = userRepository.findByWorkosUserId(workOsUser.id())
                .orElseGet(() -> userRepository.findByEmail(email).orElse(null));

        if (user == null) {
            user = new User();
            user.setFullName((workOsUser.firstName() + " " + workOsUser.lastName()).trim());
            if (user.getFullName().isBlank()) user.setFullName(email);
            user.setEmail(email);
            user.setRole(UserRole.APPLICANT);
            user.setWorkosUserId(workOsUser.id());
            user.setProfileComplete(false);
            user = userRepository.save(user);
        } else if (user.getWorkosUserId() == null) {
            user.setWorkosUserId(workOsUser.id());
            user = userRepository.save(user);
        }

        return new LoginResponse(user, jwtService.generateToken(user.getEmail()));
    }

    public LoginResponse completeProfile(String email, ProfileCompletion profile) {
        User user = userRepository.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists"));
        user.setVisaType(profile.getVisaType().trim());
        user.setAcademicLevel(profile.getAcademicLevel());
        user.setProgramEndDate(profile.getProgramEndDate());
        user.setCurrentLocation(profile.getCurrentLocation());
        user.setUpcomingTravelDate(profile.getUpcomingTravelDate());
        user.setProfileComplete(true);
        return new LoginResponse(userRepository.save(user));
    }
}
