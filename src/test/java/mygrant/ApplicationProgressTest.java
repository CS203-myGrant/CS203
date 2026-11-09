package mygrant;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import mygrant.user.*;

class ApplicationProgressTest {
    @Test
    void newTrackerUsesSignedInUsersVisaWithoutInventingProgress() {
        var jdbc=mock(JdbcTemplate.class);
        var users=mock(UserRepository.class);
        var user=new User(); user.setId(4L); user.setVisaType("F-1");
        when(users.findByEmail("owner")).thenReturn(Optional.of(user));
        var result=new ApplicationProgressService(jdbc,users).get("owner");
        assertEquals("F-1",result.details().visaType());
        assertTrue(result.details().completedSteps().isEmpty());
        assertNull(result.updatedAt());
        verify(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<ApplicationProgressService.Progress>>any(),eq(4L));
    }

    @Test
    void savesCompletedStepsAsMaskAndReadsBackUsingAuthenticatedOwner() {
        var jdbc=mock(JdbcTemplate.class);
        var users=mock(UserRepository.class);
        var user=new User(); user.setId(9L);
        when(users.findByEmail("owner")).thenReturn(Optional.of(user));
        var details=new ApplicationProgressService.Details("F-1","Singapore","AA123",null,null,"Notes",List.of(1,3,7));
        var saved=new ApplicationProgressService.Progress(details,Instant.now());
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<ApplicationProgressService.Progress>>any(),eq(9L))).thenReturn(List.of(saved));
        assertEquals(saved,new ApplicationProgressService(jdbc,users).save("owner",details));
        verify(jdbc).update(anyString(),eq(9L),eq("F-1"),eq("Singapore"),eq("AA123"),isNull(),isNull(),eq("Notes"),eq(69));
    }

    @Test
    void routesUsePrincipalAndRejectInvalidSteps() throws Exception {
        var service=mock(ApplicationProgressService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new ApplicationProgressController(service)).build();
        mvc.perform(get("/api/v1/profiles/me/application-progress").principal(()->"owner")).andExpect(status().isOk());
        verify(service).get("owner");
        mvc.perform(put("/api/v1/profiles/me/application-progress").principal(()->"owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"visaType":"F-1","interviewLocation":"","ds160Confirmation":"","notes":"","completedSteps":[8]}
                        """)).andExpect(status().isBadRequest());
        verify(service,never()).save(anyString(),any());
        mvc.perform(put("/api/v1/profiles/me/application-progress").principal(()->"owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"visaType":"F-1","interviewLocation":"","ds160Confirmation":"","notes":"","completedSteps":[1,2]}
                        """)).andExpect(status().isOk());
        verify(service).save(eq("owner"),argThat(d->d.completedSteps().equals(List.of(1,2))));
    }
}
