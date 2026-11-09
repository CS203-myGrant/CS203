package mygrant.workos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Optional;

import mygrant.account.AccountPurgeService;
import mygrant.common.ApiError;
import mygrant.user.User;
import mygrant.user.UserRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class WorkOsWebhookControllerTest {

    @Mock
    private WorkOsWebhookVerifier verifier;

    @Mock
    private WelcomeEmailService welcomeEmailService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AccountPurgeService accountPurgeService;

    @Test
    void userDeletedEventPurgesTheMatchingLocalUser() throws IOException {
        String payload = """
                {
                  "event": "user.deleted",
                  "data": {"id": "user_workos_123"}
                }
                """;
        User user = new User();
        user.setId(42L);

        when(verifier.isValid(payload, "signature")).thenReturn(true);
        when(userRepository.findByWorkosUserId("user_workos_123"))
                .thenReturn(Optional.of(user));

        WorkOsWebhookController controller = new WorkOsWebhookController(
                verifier, welcomeEmailService, new ObjectMapper(),
                userRepository, accountPurgeService);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ResponseEntity<ApiError> response = controller.receive("signature", request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(accountPurgeService).purge(42L);
    }
}
