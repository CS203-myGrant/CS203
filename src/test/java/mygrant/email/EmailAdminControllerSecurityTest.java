package mygrant.email;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import mygrant.auth.JwtAuthenticationFilter;
import mygrant.auth.JwtService;
import mygrant.auth.SecurityConfig;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

@WebMvcTest(EmailAdminController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class EmailAdminControllerSecurityTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-10-24");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DailyDigestService digestService;

    @MockBean
    private EmailDispatcher dispatcher;

    @MockBean
    private EmailSender sender;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private UserRepository userRepository;

    @BeforeEach
    void configured() {
        when(sender.isConfigured()).thenReturn(true);
        when(digestService.today(any())).thenReturn(TODAY);
        when(dispatcher.dispatchDue(any())).thenReturn(new EmailDispatcher.Result(2, 0, 0, 1));
    }

    @Test
    void digestTriggerRequiresSignIn() throws Exception {
        mockMvc.perform(post("/api/v1/admin/email/digest/run")).andExpect(status().isUnauthorized());
    }

    @Test
    void applicantsAndManagersCannotTriggerDigests() throws Exception {
        for (UserRole role : new UserRole[] {UserRole.APPLICANT, UserRole.MANAGER}) {
            stubToken(role);
            mockMvc.perform(post("/api/v1/admin/email/digest/run").header("Authorization", "Bearer token"))
                    .andExpect(status().isForbidden());
        }
        verify(digestService, never()).buildDigests(any(), any());
    }

    @Test
    void adminBuildsTodaysDigestsAndSeesTheCounts() throws Exception {
        stubToken(UserRole.ADMIN);
        when(digestService.buildDigests(eq(TODAY), any())).thenReturn(3);

        mockMvc.perform(post("/api/v1/admin/email/digest/run").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.digestDate").value("2026-10-24"))
                .andExpect(jsonPath("$.digestsCreated").value(3))
                .andExpect(jsonPath("$.emailsSent").value(2))
                .andExpect(jsonPath("$.emailsSkipped").value(1));
    }

    @Test
    void adminCanBuildAnEarlierDate() throws Exception {
        stubToken(UserRole.ADMIN);
        when(digestService.buildDigests(eq(LocalDate.parse("2026-10-23")), any())).thenReturn(1);

        mockMvc.perform(post("/api/v1/admin/email/digest/run?date=2026-10-23").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.digestDate").value("2026-10-23"))
                .andExpect(jsonPath("$.digestsCreated").value(1));
    }

    @Test
    void aFutureDateIsRejectedSoTheRealDigestIsNotSuppressed() throws Exception {
        stubToken(UserRole.ADMIN);

        mockMvc.perform(post("/api/v1/admin/email/digest/run?date=2026-10-25").header("Authorization", "Bearer token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("2026-10-24")));
        verify(digestService, never()).buildDigests(any(), any());
    }

    @Test
    void aMalformedDateIsA400ApiError() throws Exception {
        stubToken(UserRole.ADMIN);

        mockMvc.perform(post("/api/v1/admin/email/digest/run?date=24-10-2026").header("Authorization", "Bearer token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for date"));
    }

    @Test
    void withoutResendTheTriggerSaysSo() throws Exception {
        stubToken(UserRole.ADMIN);
        when(sender.isConfigured()).thenReturn(false);

        mockMvc.perform(post("/api/v1/admin/email/digest/run").header("Authorization", "Bearer token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
        verify(digestService, never()).buildDigests(any(), any());
    }

    private void stubToken(UserRole role) {
        User user = new User();
        user.setEmail("email-admin@example.com");
        user.setRole(role);
        when(jwtService.extractSubject("token")).thenReturn(user.getEmail());
        when(jwtService.extractRole("token")).thenReturn(role.name());
        when(jwtService.isAccessTokenValid("token", user.getEmail())).thenReturn(true);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }
}
