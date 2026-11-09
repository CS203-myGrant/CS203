package mygrant.manager;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import mygrant.auth.JwtService;
import mygrant.user.User;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ManagerMembershipIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;

    @Test
    void managerCanAddListAndRemoveApplicant() throws Exception {
        User manager = account(UserRole.MANAGER, "manager");
        User applicant = account(UserRole.APPLICANT, "applicant");

        mvc.perform(post("/api/v1/manager/members/" + applicant.getId())
                        .header("Authorization", token(manager)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(applicant.getId()))
                .andExpect(jsonPath("$.email").value(applicant.getEmail()));

        mvc.perform(get("/api/v1/manager/members").header("Authorization", token(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(applicant.getId()));

        mvc.perform(delete("/api/v1/manager/members/" + applicant.getId())
                        .header("Authorization", token(manager)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/manager/members").header("Authorization", token(manager)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0]").doesNotExist());
    }

    @Test
    void membershipIsPrivateToManagerAndOnlyApplicantsCanBeLinked() throws Exception {
        User manager = account(UserRole.MANAGER, "manager");
        User otherManager = account(UserRole.MANAGER, "other-manager");
        User applicant = account(UserRole.APPLICANT, "applicant");
        User admin = account(UserRole.ADMIN, "admin");

        mvc.perform(post("/api/v1/manager/members/" + applicant.getId())
                        .header("Authorization", token(manager)))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/manager/members").header("Authorization", token(otherManager)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0]").doesNotExist());
        mvc.perform(get("/api/v1/manager/members").header("Authorization", token(admin)))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/manager/members/" + applicant.getId())
                        .header("Authorization", token(manager)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/manager/members/" + otherManager.getId())
                        .header("Authorization", token(manager)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/manager/members").header("Authorization", token(applicant)))
                .andExpect(status().isForbidden());
    }

    @Test
    void removingMissingMembershipReturnsNotFound() throws Exception {
        User manager = account(UserRole.MANAGER, "manager");
        User applicant = account(UserRole.APPLICANT, "applicant");

        mvc.perform(delete("/api/v1/manager/members/" + applicant.getId())
                        .header("Authorization", token(manager)))
                .andExpect(status().isNotFound());
    }

    private User account(UserRole role, String label) {
        User user = new User(label, label + "-" + UUID.randomUUID() + "@example.org",
                "unused", "F-1", null, null);
        user.setRole(role);
        return users.saveAndFlush(user);
    }

    private String token(User user) {
        return "Bearer " + jwt.generateAccessToken(user);
    }
}
