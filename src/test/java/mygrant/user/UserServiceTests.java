package mygrant.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import java.time.Instant;
import java.time.LocalDate;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import mygrant.auth.JwtService;
import mygrant.auth.RefreshToken;
import mygrant.auth.RefreshTokenRepository;
import mygrant.common.DuplicateEmailException;
import mygrant.common.InvalidCredentialsException;
import mygrant.user.dto.LoginResponse;
import mygrant.user.dto.UserCreation;
import mygrant.user.dto.UserLogin;

/** Verifies registration persists the optional travel fields whether or not they are supplied. */
@ExtendWith(MockitoExtension.class)
class UserServiceTests {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private BCryptPasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Captor
    private ArgumentCaptor<User> saved;

    @Captor
    private ArgumentCaptor<UserProfile> savedProfile;

    @Test
    void registersWithoutTravelFields() {
        stubSuccessfulRegistration();
        LoginResponse response = service().createUser(registration());

        verify(userRepository).save(saved.capture());
        verify(userProfileRepository).save(savedProfile.capture());

        assertThat(savedProfile.getValue().getVisaType()).isEqualTo("F-1");
        assertThat(savedProfile.getValue().getAcademicLevel()).isEqualTo("Master's");
        assertThat(savedProfile.getValue().getProgramEndDate())
                .isEqualTo(LocalDate.parse("2028-05-31"));
        assertThat(saved.getValue().getCurrentLocation()).isNull();
        assertThat(saved.getValue().getUpcomingTravelDate()).isNull();
        assertThat(saved.getValue().getVisaType()).isEqualTo("F-1");
        assertThat(saved.getValue().getProgramEndDate()).isEqualTo(LocalDate.parse("2028-05-31"));
        assertThat(response.getCurrentLocation()).isNull();
        assertThat(response.getUpcomingTravelDate()).isNull();
        assertThat(response.getToken()).isEqualTo("token");
    }

    @Test
    void registersWithTravelFieldsPopulated() {
        stubSuccessfulRegistration();
        UserCreation registration = registration();
        registration.setCurrentLocation("ABROAD");
        registration.setUpcomingTravelDate(LocalDate.parse("2026-12-15"));

        LoginResponse response = service().createUser(registration);

        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getCurrentLocation()).isEqualTo("ABROAD");
        assertThat(saved.getValue().getUpcomingTravelDate()).isEqualTo(LocalDate.parse("2026-12-15"));
        assertThat(response.getCurrentLocation()).isEqualTo("ABROAD");
        assertThat(response.getUpcomingTravelDate()).isEqualTo(LocalDate.parse("2026-12-15"));
    }

    @Test
    void rejectsAnExistingEmail() {
        when(userRepository.existsByEmail("student@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service().createUser(registration()))
                .isInstanceOf(DuplicateEmailException.class)
                .hasMessage("An account with that email already exists");
    }

    @Test
    void rejectsUnknownLoginEmailWithoutRevealingWhichCredentialFailed() {
        UserLogin login = new UserLogin();
        login.setUserEmail("unknown@example.com");
        login.setUserPassword("password1");
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service().loginUser(login))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");
    }

    @Test
    void rejectsIncorrectPasswordWithTheSameMessageAsUnknownEmail() {
        UserLogin login = new UserLogin();
        login.setUserEmail("student@example.com");
        login.setUserPassword("wrongpass1");
        User user = new User();
        user.setEmail("student@example.com");
        user.setPasswordHash("hash");
        when(userRepository.findByEmail("student@example.com")).thenReturn(java.util.Optional.of(user));
        when(passwordEncoder.matches("wrongpass1", "hash")).thenReturn(false);

        assertThatThrownBy(() -> service().loginUser(login))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");
    }

    @Test
    void rejectsLoginForDeletedInactiveAccount() {
        UserLogin login = new UserLogin();
        login.setUserEmail("student@example.com");
        login.setUserPassword("password1");
        User deletedUser = new User();
        deletedUser.setEmail("student@example.com");
        deletedUser.setPasswordHash("hash");
        deletedUser.anonymizeAndDeactivate(Instant.now());
        when(userRepository.findByEmail("student@example.com"))
                .thenReturn(java.util.Optional.of(deletedUser));

        assertThatThrownBy(() -> service().loginUser(login))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");
        verifyNoInteractions(jwtService);
    }

    @Test
    void updatesAUsersRole() {
        User user = new User();
        user.setId(7L);
        user.setRole(UserRole.APPLICANT);
        when(userRepository.findById(7L)).thenReturn(java.util.Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        LoginResponse response = service().updateRole(7L, UserRole.MANAGER);

        assertThat(user.getRole()).isEqualTo(UserRole.MANAGER);
        assertThat(response.getRole()).isEqualTo(UserRole.MANAGER);
        verify(userRepository).save(user);
    }

    @Test
    void rejectsRoleUpdateForAnUnknownUser() {
        when(userRepository.findById(404L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service().updateRole(404L, UserRole.MANAGER))
                .isInstanceOf(UserNotFoundException.class);
    }

    private UserCreation registration() {
        UserCreation registration = new UserCreation();
        registration.setUserName("Student");
        registration.setUserEmail("student@example.com");
        registration.setUserPassword("password1");
        registration.setVisaType("F-1");
        registration.setAcademicLevel("Master's");
        registration.setProgramEndDate(LocalDate.parse("2028-05-31"));
        return registration;
    }

    private UserService service() {
        return new UserService(
                userRepository,
                userProfileRepository,
                passwordEncoder,
                jwtService,
                refreshTokenRepository
        );
    }

    private void stubSuccessfulRegistration() {
        when(userRepository.existsByEmail("student@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password1")).thenReturn("hash");
        when(userProfileRepository.save(any(UserProfile.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtService.generateAccessToken(any(User.class))).thenReturn("token");
        when(jwtService.generateRefreshToken(any(User.class))).thenReturn("refresh-token");
        when(jwtService.extractExpiration("refresh-token")).thenReturn(java.time.Instant.now().plusSeconds(3600));
        
    }

    @Test
    void separateLoginsCreateIndependentRefreshTokensForTheSameUser() {
        User user = activeUser();
        UserLogin login = loginRequest();

        when(userRepository.findByEmail("student@example.com"))
                .thenReturn(java.util.Optional.of(user));
        when(passwordEncoder.matches("password1", "hash")).thenReturn(true);

        when(jwtService.generateAccessToken(user))
                .thenReturn("access-token-a", "access-token-b");
        when(jwtService.generateRefreshToken(user))
                .thenReturn("refresh-token-a", "refresh-token-b");
        when(jwtService.extractExpiration("refresh-token-a"))
                .thenReturn(Instant.now().plusSeconds(3600));
        when(jwtService.extractExpiration("refresh-token-b"))
                .thenReturn(Instant.now().plusSeconds(3600));

        service().loginUser(login);
        service().loginUser(login);

        ArgumentCaptor<RefreshToken> refreshTokens =
                ArgumentCaptor.forClass(RefreshToken.class);

        verify(refreshTokenRepository, times(2)).save(refreshTokens.capture());

        assertThat(refreshTokens.getAllValues()).hasSize(2);
        assertThat(refreshTokens.getAllValues().get(0).getUserId())
                .isEqualTo(user.getId());
        assertThat(refreshTokens.getAllValues().get(1).getUserId())
                .isEqualTo(user.getId());
        assertThat(refreshTokens.getAllValues().get(0).getTokenHash())
                .isNotEqualTo(refreshTokens.getAllValues().get(1).getTokenHash());
    }

    @Test
    void logoutRevokesOnlyThatDevicesRefreshToken() {
        User user = activeUser();

        RefreshToken deviceAToken = new RefreshToken(
                user.getId(),
                sha256("refresh-token-a"),
                Instant.now().plusSeconds(3600)
        );

        RefreshToken deviceBToken = new RefreshToken(
                user.getId(),
                sha256("refresh-token-b"),
                Instant.now().plusSeconds(3600)
        );

        when(userRepository.findByEmail("student@example.com"))
                .thenReturn(java.util.Optional.of(user));

        when(refreshTokenRepository.findByTokenHashAndRevokedAtIsNull(
                sha256("refresh-token-a")))
                .thenReturn(java.util.Optional.of(deviceAToken));

        service().logout("student@example.com", "refresh-token-a");

        assertThat(deviceAToken.getRevokedAt()).isNotNull();
        verify(refreshTokenRepository).save(deviceAToken);

        when(jwtService.extractSubject("refresh-token-b"))
                .thenReturn("student@example.com");
        when(jwtService.extractTokenId("refresh-token-b"))
                .thenReturn("device-b");
        when(jwtService.isRefreshTokenValid(
                "refresh-token-b", "student@example.com"))
                .thenReturn(true);

        when(refreshTokenRepository.findByTokenHashAndRevokedAtIsNull(
                sha256("refresh-token-b")))
                .thenReturn(java.util.Optional.of(deviceBToken));

        when(jwtService.generateAccessToken(user))
                .thenReturn("new-access-token-b");
        when(jwtService.generateRefreshToken(user))
                .thenReturn("new-refresh-token-b");
        when(jwtService.extractExpiration("new-refresh-token-b"))
                .thenReturn(Instant.now().plusSeconds(3600));

        LoginResponse refreshed = service().refresh("refresh-token-b");

        assertThat(refreshed.getToken()).isEqualTo("new-access-token-b");
        assertThat(refreshed.getRefreshToken()).isEqualTo("new-refresh-token-b");
    }

    @Test
    void loggedOutDeviceCannotRefreshAgain() {
        User user = activeUser();

        RefreshToken deviceAToken = new RefreshToken(
                user.getId(),
                sha256("refresh-token-a"),
                Instant.now().plusSeconds(3600)
        );

        when(userRepository.findByEmail("student@example.com"))
                .thenReturn(java.util.Optional.of(user));

        when(refreshTokenRepository.findByTokenHashAndRevokedAtIsNull(
                sha256("refresh-token-a")))
                .thenReturn(java.util.Optional.of(deviceAToken));

        service().logout("student@example.com", "refresh-token-a");

        when(jwtService.extractSubject("refresh-token-a"))
                .thenReturn("student@example.com");
        when(jwtService.extractTokenId("refresh-token-a"))
                .thenReturn("device-a");
        when(jwtService.isRefreshTokenValid(
                "refresh-token-a", "student@example.com"))
                .thenReturn(true);

        assertThatThrownBy(() -> service().refresh("refresh-token-a"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid refresh token");
    }

    private User activeUser() {
        User user = new User();
        user.setId(1L);
        user.setEmail("student@example.com");
        user.setPasswordHash("hash");
        user.setRole(UserRole.APPLICANT);
        return user;
    }

    private UserLogin loginRequest() {
        UserLogin login = new UserLogin();
        login.setUserEmail("student@example.com");
        login.setUserPassword("password1");
        return login;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
