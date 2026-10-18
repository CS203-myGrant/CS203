package mygrant.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import mygrant.auth.JwtService;
import mygrant.common.DuplicateEmailException;
import mygrant.common.GlobalExceptionHandler;
import mygrant.common.InvalidCredentialsException;
import mygrant.user.dto.LoginResponse;

@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    // JwtAuthenticationFilter is discovered in the MVC test context even though
    // filters are disabled for these controller-focused tests.
    @MockBean
    private JwtService jwtService;

    @MockBean
    private UserRepository userRepository;

    @Test
    void registerReturnsCreatedUserAndToken() throws Exception {
        when(userService.createUser(any())).thenReturn(successResponse());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userName": "Student",
                                  "userEmail": "student@example.com",
                                  "userPassword": "Password1",
                                  "visaType": "F-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userEmail").value("student@example.com"))
                .andExpect(jsonPath("$.token").value("token"));
    }

    @Test
    void loginReturnsUserAndToken() throws Exception {
        when(userService.loginUser(any())).thenReturn(successResponse());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userEmail": "student@example.com",
                                  "userPassword": "Password1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("token"));
    }

    @Test
    void invalidLoginReturnsUnauthorized() throws Exception {
        when(userService.loginUser(any()))
                .thenThrow(new InvalidCredentialsException("Invalid email or password"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userEmail\":\"student@example.com\",\"userPassword\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void duplicateRegistrationReturnsConflict() throws Exception {
        when(userService.createUser(any()))
                .thenThrow(new DuplicateEmailException());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userName": "Student",
                                  "userEmail": "student@example.com",
                                  "userPassword": "Password1",
                                  "visaType": "F-1"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("An account with that email already exists"));
    }

    @Test
    void invalidRegistrationReturnsValidationErrors() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userName": "",
                                  "userEmail": "not-an-email",
                                  "userPassword": "short",
                                  "visaType": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.validationErrors.userEmail").exists())
                .andExpect(jsonPath("$.validationErrors.userPassword").exists());
    }

    @Test
    void malformedJsonReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userEmail\":\"student@example.com\","))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Request body is invalid. Check the JSON and date values."));
    }

    private LoginResponse successResponse() {
        LoginResponse response = new LoginResponse();
        response.setUserEmail("student@example.com");
        response.setToken("token");
        return response;
    }
}
