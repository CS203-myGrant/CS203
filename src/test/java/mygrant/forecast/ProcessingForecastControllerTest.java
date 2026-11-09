package mygrant.forecast;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import mygrant.auth.JwtAuthenticationFilter;
import mygrant.auth.JwtService;
import mygrant.auth.SecurityConfig;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

@WebMvcTest(ProcessingForecastController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ProcessingForecastControllerTest {
    @Autowired MockMvc mvc;
    @MockBean ProcessingForecastService forecasts;
    @MockBean ForecastRiskService risks;
    @MockBean JwtService jwt;
    @MockBean UserRepository users;

    @Test
    void authenticationIsRequiredAndCallerIdentityIsUsed() throws Exception {
        mvc.perform(get("/api/v1/forecast/processing-times").param("formType", "I-765"))
                .andExpect(status().isUnauthorized());
        authenticate("first@example.com");
        when(forecasts.forecast(eq("first@example.com"), eq("I-765"), eq(null), eq(null)))
                .thenReturn(ProcessingForecastService.Forecast.unavailable("I-765", "MISSING_FILING_DATE"));
        mvc.perform(get("/api/v1/forecast/processing-times").param("formType", "I-765")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.reason").value("MISSING_FILING_DATE"));
        verify(forecasts).forecast("first@example.com", "I-765", null, null);
    }

    @Test
    void invalidFormUsesSharedApiError() throws Exception {
        authenticate("first@example.com");
        when(forecasts.forecast("first@example.com", "bad", null, null))
                .thenThrow(new IllegalArgumentException("formType must look like I-765"));
        mvc.perform(get("/api/v1/forecast/processing-times").param("formType", "bad")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    void seriesSelectionIsAuthenticatedAndScopedToCaller() throws Exception {
        String body = """
                {"formType":"I-765","formCategory":"OPT","office":"NBC"}
                """;
        mvc.perform(put("/api/v1/forecast/processing-times/selection")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        authenticate("first@example.com");
        when(forecasts.selectSeries("first@example.com", "I-765", "OPT", "NBC"))
                .thenReturn(new ProcessingForecastService.Series("OPT", "NBC"));
        mvc.perform(put("/api/v1/forecast/processing-times/selection")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.office").value("NBC"));
        verify(forecasts).selectSeries("first@example.com", "I-765", "OPT", "NBC");
    }

    @Test
    void alertsArePrivateToTheAuthenticatedAccount() throws Exception {
        mvc.perform(get("/api/v1/forecast/deadline-alerts"))
                .andExpect(status().isUnauthorized());
        authenticate("first@example.com");
        when(risks.alertsFor("first@example.com")).thenReturn(java.util.List.of());
        mvc.perform(get("/api/v1/forecast/deadline-alerts")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        verify(risks).alertsFor("first@example.com");
    }

    private void authenticate(String email) {
        User user = new User();
        user.setEmail(email);
        user.setRole(UserRole.APPLICANT);
        when(jwt.extractSubject("token")).thenReturn(email);
        when(jwt.extractRole("token")).thenReturn(UserRole.APPLICANT.name());
        when(jwt.isAccessTokenValid("token", email)).thenReturn(true);
        when(users.findByEmail(email)).thenReturn(Optional.of(user));
    }
}
