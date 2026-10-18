package mygrant.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
}