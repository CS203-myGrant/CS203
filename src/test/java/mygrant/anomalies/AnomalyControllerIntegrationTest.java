package mygrant.anomalies;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import mygrant.auth.JwtAuthenticationFilter;
import mygrant.auth.JwtService;
import mygrant.auth.SecurityConfig;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

@WebMvcTest(AnomalyController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class AnomalyControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AnomalyRepository anomalyRepository;

    @MockBean
    private AnomalyService anomalyService;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private UserRepository userRepository;

    @Test
    void applicantCannotListAnomalies() throws Exception {
        stubToken(UserRole.APPLICANT);

        mockMvc.perform(get("/api/v1/anomalies")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanFilterOpenAnomaliesByType() throws Exception {
        stubToken(UserRole.ADMIN);

        Anomaly anomaly = new Anomaly(
                AnomalyType.UNCERTAIN_RULE_LINK,
                12L,
                7L,
                null,
                "Title-only match"
        );

        when(anomalyRepository.findByTypeAndStatusOrderByCreatedAtDesc(
                AnomalyType.UNCERTAIN_RULE_LINK,
                AnomalyStatus.OPEN,
                org.springframework.data.domain.PageRequest.of(0, 20)
        )).thenReturn(new PageImpl<>(List.of(anomaly)));

        mockMvc.perform(get("/api/v1/anomalies")
                        .param("type", "UNCERTAIN_RULE_LINK")
                        .param("status", "OPEN")
                        .param("page", "0")
                        .param("size", "20")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].type").value("UNCERTAIN_RULE_LINK"))
                .andExpect(jsonPath("$.content[0].status").value("OPEN"));
    }

    @Test
    void applicantCannotResolveAnomaly() throws Exception {
        stubToken(UserRole.APPLICANT);

        mockMvc.perform(patch("/api/v1/anomalies/12")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "IGNORED",
                                  "note": "Duplicate source report."
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanResolveDateConflict() throws Exception {
        stubToken(UserRole.ADMIN);

        Anomaly resolved = new Anomaly(
                AnomalyType.EFFECTIVE_DATE_CONFLICT,
                12L,
                7L,
                null,
                "Different effective dates"
        );
        resolved.resolve(
                AnomalyStatus.RESOLVED,
                "Confirmed against the Federal Register.",
                LocalDate.of(2026, 11, 1),
                "admin@example.com"
        );

        when(anomalyService.resolve(
                eq(12L),
                eq(AnomalyStatus.RESOLVED),
                eq("Confirmed against the Federal Register."),
                eq(LocalDate.of(2026, 11, 1)),
                eq("admin@example.com")
        )).thenReturn(resolved);

        mockMvc.perform(patch("/api/v1/anomalies/12")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "RESOLVED",
                                  "note": "Confirmed against the Federal Register.",
                                  "correctEffectiveDate": "2026-11-01"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.correctEffectiveDate").value("2026-11-01"))
                .andExpect(jsonPath("$.resolutionNote")
                        .value("Confirmed against the Federal Register."));

        verify(anomalyService).resolve(
                12L,
                AnomalyStatus.RESOLVED,
                "Confirmed against the Federal Register.",
                LocalDate.of(2026, 11, 1),
                "admin@example.com"
        );
    }

    private void stubToken(UserRole role) {
        User user = new User();
        user.setEmail("admin@example.com");
        user.setRole(role);

        when(jwtService.extractSubject("token")).thenReturn(user.getEmail());
        when(jwtService.extractRole("token")).thenReturn(role.name());
        when(jwtService.isAccessTokenValid("token", user.getEmail())).thenReturn(true);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }
}