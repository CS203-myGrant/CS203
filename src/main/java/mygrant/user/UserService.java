package mygrant.user;

import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;

import mygrant.auth.JwtService;
import mygrant.auth.RefreshToken;
import mygrant.auth.RefreshTokenRepository;
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
    private final RefreshTokenRepository refreshTokenRepository;

    public UserService(
            UserRepository userRepository,
            UserProfileRepository userProfileRepository,
            BCryptPasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenRepository refreshTokenRepository) {
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenRepository = refreshTokenRepository;
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
        user.setProfileComplete(false);
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
        return tokens(savedUser);
    }

    public LoginResponse loginUser(UserLogin userLogin) {
        Optional<User> optionalUser = userRepository.findByEmail(
                userLogin.getUserEmail().trim().toLowerCase());

        if (optionalUser.isEmpty()) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        User user = optionalUser.get();

        if (!user.isActive() || user.getPasswordHash() == null
                || !passwordEncoder.matches(userLogin.getUserPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        return tokens(user);
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
        } else if (!user.isActive()) {
            throw new InvalidCredentialsException("Invalid email or password");
        } else if (user.getWorkosUserId() == null) {
            user.setWorkosUserId(workOsUser.id());
            user = userRepository.save(user);
        }

        return tokens(user);
    }

    @Transactional
    public LoginResponse refresh(String refreshToken) {
        String email;
        String tokenId;
        try {
            email = jwtService.extractSubject(refreshToken);
            tokenId = jwtService.extractTokenId(refreshToken);
        } catch (RuntimeException exception) {
            throw new InvalidCredentialsException("Invalid refresh token");
        }
        if (tokenId == null || !jwtService.isRefreshTokenValid(refreshToken, email)) {
            throw new InvalidCredentialsException("Invalid refresh token");
        }
        User user = userRepository.findByEmail(email)
                .filter(User::isActive)
                .orElseThrow(() -> new InvalidCredentialsException("Invalid refresh token"));
        RefreshToken stored = refreshTokenRepository
                .findByTokenHashAndRevokedAtIsNull(hash(refreshToken))
                .filter(token -> Objects.equals(token.getUserId(), user.getId()) && token.isUsable())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid refresh token"));
        stored.revoke();
        refreshTokenRepository.save(stored);
        return tokens(user);
    }

    @Transactional
    public void logout(String email, String refreshToken) {
        User user = userRepository.findByEmail(email.trim().toLowerCase())
                .filter(User::isActive)
                .orElseThrow(() ->
                        new InvalidCredentialsException("Invalid logout request"));

        refreshTokenRepository.findByTokenHashAndRevokedAtIsNull(hash(refreshToken))
                .filter(token -> Objects.equals(token.getUserId(), user.getId()))
                .ifPresent(token -> {
                    token.revoke();
                    refreshTokenRepository.save(token);
                });
    }

    private LoginResponse tokens(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);
        refreshTokenRepository.save(new RefreshToken(user.getId(), hash(refreshToken),
                jwtService.extractExpiration(refreshToken)));
        return new LoginResponse(user, accessToken, refreshToken);
    }

    private String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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

    @Transactional
    public LoginResponse updateRole(Long userId, UserRole role) {
        if (role == null) {
            throw new IllegalArgumentException("Role is required");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        user.setRole(role);
        return new LoginResponse(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public Page<LoginResponse> listUsers(Pageable pageable) {
        return userRepository.findAllByActiveTrueOrderByIdAsc(pageable)
                .map(LoginResponse::new);
    }
}
