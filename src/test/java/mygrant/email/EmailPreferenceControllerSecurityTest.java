package mygrant.email;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import mygrant.auth.JwtAuthenticationFilter;
import mygrant.auth.JwtService;
import mygrant.auth.SecurityConfig;
import mygrant.common.ProfileValidationException;
import mygrant.email.dto.EmailPreferencesResponse;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

@WebMvcTest(EmailPreferenceController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class EmailPreferenceControllerSecurityTest {

    private static final String EMAIL = "student@example.com";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EmailPreferenceService preferenceService;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private UserRepository userRepository;

    @Test
    void preferencesRequireSignIn() throws Exception {
        mockMvc.perform(get("/api/v1/profiles/me/email-preferences"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/profiles/me/email-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"criticalDelivery\":\"OFF\",\"otherDelivery\":\"OFF\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signedInUserReadsTheirSettingsAndEligibility() throws Exception {
        stubToken();
        when(preferenceService.get(EMAIL)).thenReturn(new EmailPreferencesResponse(
                DeliveryMode.IMMEDIATE, DeliveryMode.DIGEST, EMAIL, false, IneligibleReason.EMAIL_NOT_VERIFIED));

        mockMvc.perform(get("/api/v1/profiles/me/email-preferences").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criticalDelivery").value("IMMEDIATE"))
                .andExpect(jsonPath("$.otherDelivery").value("DIGEST"))
                .andExpect(jsonPath("$.canReceiveEmail").value(false))
                .andExpect(jsonPath("$.ineligibleReason").value("EMAIL_NOT_VERIFIED"));
    }

    @Test
    void missingModeIsA400ApiError() throws Exception {
        stubToken();

        mockMvc.perform(put("/api/v1/profiles/me/email-preferences")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"criticalDelivery\":\"OFF\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.validationErrors.otherDelivery").exists());
    }

    @Test
    void unknownModeIsA400ApiError() throws Exception {
        stubToken();
        when(preferenceService.update(eq(EMAIL), any())).thenThrow(new ProfileValidationException(
                Map.of("criticalDelivery", "Must be one of IMMEDIATE, DIGEST or OFF")));

        mockMvc.perform(put("/api/v1/profiles/me/email-preferences")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"criticalDelivery\":\"WEEKLY\",\"otherDelivery\":\"OFF\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.criticalDelivery")
                        .value("Must be one of IMMEDIATE, DIGEST or OFF"));
    }

    private void stubToken() {
        User user = new User();
        user.setEmail(EMAIL);
        user.setRole(UserRole.APPLICANT);
        when(jwtService.extractSubject("token")).thenReturn(EMAIL);
        when(jwtService.extractRole("token")).thenReturn("APPLICANT");
        when(jwtService.isAccessTokenValid("token", EMAIL)).thenReturn(true);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    }
}
