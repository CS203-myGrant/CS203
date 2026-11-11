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
        user.setRole(UserRole.APPLICANT);
        user.setProfileComplete(false);
        user.setEmailVerified(false);
        user.setPasswordHash(passwordEncoder.encode(userCreation.getUserPassword()));

        User savedUser;
        try {
            // The database unique constraint is still required because two requests
            // can pass existsByEmail at the same time.
            savedUser = userRepository.save(user);
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateEmailException();
        }
        UserProfile profile = userProfileRepository.save(
                UserProfile.registrationProfile(
                        savedUser.getId(),
                        userCreation.getVisaType(),
                        userCreation.getAcademicLevel(),
                        userCreation.getProgramEndDate(),
                        userCreation.getCurrentLocation(),
                        userCreation.getUpcomingTravelDate()
                )
        );
        return tokens(savedUser, profile);
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

        return tokens(user, profileFor(user));
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
            user.setEmailVerified(true);
            user.setProfileComplete(false);
            user = userRepository.save(user);
        } else if (!user.isActive()) {
            throw new InvalidCredentialsException("Invalid email or password");
        } else if (user.getWorkosUserId() == null) {
            user.setWorkosUserId(workOsUser.id());
            user.setEmailVerified(true);
            user = userRepository.save(user);
        }

        return tokens(user, profileFor(user));
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
        return tokens(user, profileFor(user));
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

    private UserProfile profileFor(User user) {
    return userProfileRepository.findById(user.getId()).orElse(null);
    }

    private LoginResponse tokens(User user, UserProfile profile) {
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);
        refreshTokenRepository.save(new RefreshToken(user.getId(), hash(refreshToken),
                jwtService.extractExpiration(refreshToken)));
        return new LoginResponse(user, profile, accessToken, refreshToken);
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

    @Transactional
    public LoginResponse completeProfile(String email, ProfileCompletion completion) {
        User user = userRepository.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated user no longer exists"));

        UserProfile profile = userProfileRepository.findById(user.getId())
                .orElseGet(() -> UserProfile.empty(user.getId()));

        profile.setVisaType(completion.getVisaType().trim());
        profile.setAcademicLevel(completion.getAcademicLevel());
        profile.setProgramEndDate(completion.getProgramEndDate());
        profile.setCurrentLocation(completion.getCurrentLocation());
        profile.setUpcomingTravelDate(completion.getUpcomingTravelDate());

        user.setProfileComplete(true);

        User savedUser = userRepository.save(user);
        UserProfile savedProfile = userProfileRepository.save(profile);

        return new LoginResponse(savedUser, savedProfile);
    }

    @Transactional(readOnly = true)
    public LoginResponse currentUser(String email) {
        User user = userRepository.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated user no longer exists"));

        UserProfile profile = userProfileRepository.findById(user.getId())
                .orElse(null);

        return new LoginResponse(user, profile);
    }

    @Transactional
    public LoginResponse updateRole(Long userId, UserRole role) {
        if (role == null) {
            throw new IllegalArgumentException("Role is required");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        user.setRole(role);

        User saved = userRepository.save(user);
        UserProfile profile = userProfileRepository.findById(saved.getId())
                .orElse(null);

        return new LoginResponse(saved, profile);
    }

    @Transactional(readOnly = true)
    public Page<LoginResponse> listUsers(Pageable pageable) {
        return userRepository.findAllByActiveTrueOrderByIdAsc(pageable)
                .map(user -> new LoginResponse(
                        user,
                        userProfileRepository.findById(user.getId()).orElse(null)
                ));
    }
    }
