package mygrant.ingestion.uscis;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import mygrant.ingestion.connector.ConnectorHealth;
import mygrant.ingestion.connector.SourceIdentity;
import mygrant.policies.SourceType;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

@WebMvcTest(UscisProcessingTimeController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class UscisProcessingTimeControllerTest {
    private static final String BASE = "/api/v1/ingestion/uscis-processing-times";
    @Autowired MockMvc mvc;
    @MockBean UscisProcessingTimeImporter importer;
    @MockBean JwtService jwt;
    @MockBean UserRepository users;

    @Test
    void onlyAdminCanImportOrInspectHealth() throws Exception {
        mvc.perform(get(BASE + "/health")).andExpect(status().isUnauthorized());
        stubRole(UserRole.APPLICANT);
        mvc.perform(get(BASE + "/health").header("Authorization", "Bearer token"))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/saved").header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        stubRole(UserRole.ADMIN);
        when(importer.health()).thenReturn(ConnectorHealth.notChecked(
                new SourceIdentity("USCIS_PROCESSING_TIMES", SourceType.PRIMARY)));
        when(importer.ingest(anyString())).thenReturn(new UscisProcessingTimeImporter.Result(
                1L, false, "I-765", "OPT", "SCOPS", LocalDate.of(2026, 9, 15),
                80, new BigDecimal("4.5"), "MONTHS", "https://egov.uscis.gov/processing-times/"));
        mvc.perform(get(BASE + "/health").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source.id").value("USCIS_PROCESSING_TIMES"));
        mvc.perform(post(BASE + "/saved").header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.percentile").value(80))
                .andExpect(jsonPath("$.durationUnit").value("MONTHS"));
    }

    private void stubRole(UserRole role) {
        User user = new User();
        user.setEmail("uscis-security@example.com");
        user.setRole(role);
        when(jwt.extractSubject("token")).thenReturn(user.getEmail());
        when(jwt.extractRole("token")).thenReturn(role.name());
        when(jwt.isAccessTokenValid("token", user.getEmail())).thenReturn(true);
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }
}
