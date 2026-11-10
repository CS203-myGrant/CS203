package mygrant.impact;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.time.LocalDate;
import java.util.Optional;
import mygrant.auth.*;
import mygrant.user.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(QualityMetricsController.class)
@Import({SecurityConfig.class,JwtAuthenticationFilter.class})
class QualityMetricsControllerSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean QualityMetricsService service;
    @MockBean JwtService jwt;
    @MockBean UserRepository users;
    void token(UserRole role) {
        var user=new User();user.setEmail("metrics@example.org");user.setRole(role);
        when(jwt.extractSubject("token")).thenReturn(user.getEmail());
        when(jwt.extractRole("token")).thenReturn(role.name());
        when(jwt.isAccessTokenValid("token",user.getEmail())).thenReturn(true);
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }
    @Test void onlyAdminsMayReadMetrics() throws Exception {
        mvc.perform(get("/api/v1/admin/metrics")).andExpect(status().isUnauthorized());
        for(var role:new UserRole[]{UserRole.APPLICANT,UserRole.MANAGER}){
            token(role);
            mvc.perform(get("/api/v1/admin/metrics").header("Authorization","Bearer token")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
        token(UserRole.ADMIN);
        mvc.perform(get("/api/v1/admin/metrics").header("Authorization","Bearer token")
                .param("from","2032-04-05").param("to","2032-04-06")).andExpect(status().isOk());
        verify(service).metrics(LocalDate.of(2032,4,5),LocalDate.of(2032,4,6));
    }
    @Test void malformedAndReversedDatesReturnBadRequest() throws Exception {
        token(UserRole.ADMIN);
        mvc.perform(get("/api/v1/admin/metrics").header("Authorization","Bearer token").param("from","yesterday"))
                .andExpect(status().isBadRequest());
        when(service.metrics(any(),any())).thenThrow(new IllegalArgumentException("Invalid date range"));
        mvc.perform(get("/api/v1/admin/metrics").header("Authorization","Bearer token")
                .param("from","2032-04-06").param("to","2032-04-05")).andExpect(status().isBadRequest());
    }
}
