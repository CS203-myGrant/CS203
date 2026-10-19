package mygrant.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import mygrant.auth.JwtAuthenticationFilter;
import mygrant.auth.JwtService;
import mygrant.auth.SecurityConfig;
import mygrant.user.UserRepository;
import mygrant.user.User;

@WebMvcTest(AccountController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class AccountControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AccountDeletionService accountDeletionService;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private UserRepository userRepository;

    @Test
    void deletionRequestRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/account/deletion-request"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deletionConfirmationRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/account/deletion-confirm")
                        .contentType("application/json")
                        .content("""
                                {"challenge":"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedApplicantCanReachProtectedEndpoint() throws Exception {
        User user = new User();
        user.setEmail("student@example.com");
        user.setRole(mygrant.user.UserRole.APPLICANT);
        when(jwtService.extractSubject("token")).thenReturn(user.getEmail());
        when(jwtService.extractRole("token")).thenReturn("APPLICANT");
        when(jwtService.isAccessTokenValid("token", user.getEmail())).thenReturn(true);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(java.util.Optional.of(user));

        mockMvc.perform(post("/api/v1/account/deletion-request")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk());
    }

    @Test
    void accessTokenWithoutRoleClaimIsRejected() throws Exception {
        User user = new User();
        user.setEmail("student@example.com");
        when(jwtService.extractSubject("token")).thenReturn(user.getEmail());
        when(jwtService.extractRole("token")).thenReturn(null);
        when(jwtService.isAccessTokenValid("token", user.getEmail())).thenReturn(true);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(java.util.Optional.of(user));

        mockMvc.perform(post("/api/v1/account/deletion-request")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isUnauthorized());
    }
}
