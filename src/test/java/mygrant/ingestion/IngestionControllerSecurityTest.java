package mygrant.ingestion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.time.Instant;
import java.util.Optional;

import mygrant.auth.JwtAuthenticationFilter;
import mygrant.auth.JwtService;
import mygrant.auth.SecurityConfig;
import mygrant.ingestion.dto.IngestionResponse;
import mygrant.ingestion.connector.ConnectorHealth;
import mygrant.ingestion.connector.SourceIdentity;
import mygrant.ingestion.federalregister.FederalRegisterIngestionService;
import mygrant.policies.SourceType;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(IngestionController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class IngestionControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PolicyIngestionService ingestionService;

    @MockBean
    private FederalRegisterIngestionService federalRegisterIngestionService;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private UserRepository userRepository;

    @Test
    void applicantCannotIngestPolicy() throws Exception {
        stubToken(UserRole.APPLICANT);

        mockMvc.perform(post("/api/v1/ingestion/documents")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanIngestPolicy() throws Exception {
        stubToken(UserRole.ADMIN);
        when(ingestionService.ingest(any()))
                .thenReturn(new IngestionResponse(1L, "policy-1", 1, Instant.now(), false));

        mockMvc.perform(post("/api/v1/ingestion/documents")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated());
    }

    @Test
    void onlyAdminCanSeeFederalRegisterConnectorHealth() throws Exception {
        stubToken(UserRole.ADMIN);
        when(federalRegisterIngestionService.health()).thenReturn(ConnectorHealth.notChecked(
                new SourceIdentity("FEDERAL_REGISTER", SourceType.PRIMARY)));
        mockMvc.perform(get("/api/v1/ingestion/federal-register/health")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source.id").value("FEDERAL_REGISTER"))
                .andExpect(jsonPath("$.status").value("NOT_CHECKED"));

        stubToken(UserRole.APPLICANT);
        mockMvc.perform(get("/api/v1/ingestion/federal-register/health")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isForbidden());
    }

    private void stubToken(UserRole role) {
        User user = new User();
        user.setEmail("security-test@example.com");
        user.setRole(role);

        when(jwtService.extractSubject("token")).thenReturn(user.getEmail());
        when(jwtService.extractRole("token")).thenReturn(role.name());
        when(jwtService.isAccessTokenValid("token", user.getEmail())).thenReturn(true);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }

    private String validRequest() {
        return """
                {
                  "externalId": "policy-1",
                  "title": "Policy title",
                  "agency": "Agency",
                  "visaType": "F-1",
                  "status": "FINAL",
                  "sourceType": "PRIMARY",
                  "publicationDate": "2026-10-19",
                  "sourceUrl": "https://example.com/policy-1",
                  "content": "Policy content"
                }
                """;
    }
}
