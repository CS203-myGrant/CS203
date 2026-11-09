package mygrant.ingestion.visabulletin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import mygrant.auth.JwtAuthenticationFilter;
import mygrant.auth.JwtService;
import mygrant.auth.SecurityConfig;
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

@WebMvcTest(VisaBulletinController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class VisaBulletinControllerTest {
    private static final String BASE = "/api/v1/ingestion/visa-bulletin";

    @Autowired MockMvc mockMvc;
    @MockBean VisaBulletinImporter importer;
    @MockBean VisaBulletinStore store;
    @MockBean VisaBulletinClient client;
    @MockBean JwtService jwtService;
    @MockBean UserRepository users;

    @Test
    void signedOutUserCannotInspectBulletin() throws Exception {
        mockMvc.perform(get(BASE + "/health")).andExpect(status().isUnauthorized());
    }

    @Test
    void applicantAndManagerCannotReadOrImportBulletin() throws Exception {
        for (UserRole role : List.of(UserRole.APPLICANT, UserRole.MANAGER)) {
            stubToken(role);
            mockMvc.perform(get(BASE + "/health").header("Authorization", "Bearer token"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get(BASE + "/entries/2026-10")
                            .header("Authorization", "Bearer token"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(BASE + "/2026-10").header("Authorization", "Bearer token"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(BASE + "/saved")
                            .header("Authorization", "Bearer token")
                            .contentType(MediaType.TEXT_HTML).content("<html></html>"))
                    .andExpect(status().isForbidden());
        }
        verify(importer, never()).importMonth(any());
        verify(importer, never()).importSaved(any());
    }

    @Test
    void adminCanInspectAndImportSavedPage() throws Exception {
        stubToken(UserRole.ADMIN);
        YearMonth month = YearMonth.of(2026, 10);
        String url = "https://travel.state.gov/bulletin";
        when(store.health()).thenReturn(new VisaBulletinStore.Health("OK", null, null,
                month, url, null, null));
        when(store.entries(month)).thenReturn(List.of(new VisaBulletinStore.EntryView(
                "FAMILY", "FINAL_ACTION", "F1", "INDIA", "CURRENT", null, url)));
        when(importer.importSaved("<html></html>"))
                .thenReturn(new VisaBulletinImporter.Result(month, 150, url));

        mockMvc.perform(get(BASE + "/health").header("Authorization", "Bearer token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OK"));
        mockMvc.perform(get(BASE + "/entries/2026-10")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].cutoffKind").value("CURRENT"));
        mockMvc.perform(post(BASE + "/saved")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.TEXT_HTML).content("<html></html>"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entriesProcessed").value(150));
    }

    @Test
    void unrelatedSavedHtmlReturnsClearErrorToAdmin() throws Exception {
        stubToken(UserRole.ADMIN);
        String html = "<html><h1>School news</h1></html>";
        when(importer.importSaved(html))
                .thenThrow(new IllegalArgumentException(
                        "This HTML does not look like a Visa Bulletin page."));
        mockMvc.perform(post(BASE + "/saved")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.TEXT_HTML).content(html))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("This HTML does not look like a Visa Bulletin page."));
    }

    @Test
    void invalidMonthIsRejectedForAdmin() throws Exception {
        stubToken(UserRole.ADMIN);
        mockMvc.perform(get(BASE + "/entries/not-a-month")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isBadRequest());
    }

    private void stubToken(UserRole role) {
        User user = new User();
        user.setEmail("bulletin-security@example.com");
        user.setRole(role);
        when(jwtService.extractSubject("token")).thenReturn(user.getEmail());
        when(jwtService.extractRole("token")).thenReturn(role.name());
        when(jwtService.isAccessTokenValid("token", user.getEmail())).thenReturn(true);
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }
}
