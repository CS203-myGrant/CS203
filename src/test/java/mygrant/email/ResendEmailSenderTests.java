package mygrant.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ResendEmailSenderTests {

    private static final EmailMessage MESSAGE = new EmailMessage("student@example.com", "Action needed: a rule",
            "<p>Hello</p>", "Hello", Map.of("List-Unsubscribe", "<https://mygrant.example/u>",
                    "List-Unsubscribe-Post", "List-Unsubscribe=One-Click"),
            "6f1c2a8e-0000-4000-8000-000000000001");

    private MockRestServiceServer server;
    private ResendEmailSender sender;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.resend.com");
        server = MockRestServiceServer.bindTo(builder).build();
        sender = new ResendEmailSender(builder.build(), "re_test_key", "myGRANT <alerts@mygrant.example>");
    }

    @Test
    void postsTheEmailWithCustomHeadersAndTheIdempotencyKey() {
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer re_test_key"))
                .andExpect(header("Idempotency-Key", MESSAGE.idempotencyKey()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.from").value("myGRANT <alerts@mygrant.example>"))
                .andExpect(jsonPath("$.to[0]").value("student@example.com"))
                .andExpect(jsonPath("$.subject").value("Action needed: a rule"))
                .andExpect(jsonPath("$.html").value("<p>Hello</p>"))
                .andExpect(jsonPath("$.text").value("Hello"))
                .andExpect(jsonPath("$.headers['List-Unsubscribe']").value("<https://mygrant.example/u>"))
                .andExpect(jsonPath("$.headers['List-Unsubscribe-Post']").value("List-Unsubscribe=One-Click"))
                .andRespond(withSuccess("{\"id\":\"49a3999c-0ce1-4ea6-ab68-afcd6dc2e794\"}", MediaType.APPLICATION_JSON));

        assertThat(sender.send(MESSAGE)).isEqualTo("49a3999c-0ce1-4ea6-ab68-afcd6dc2e794");
        server.verify();
    }

    @Test
    void rateLimitsServerErrorsAndInFlightDuplicatesAreRetryable() {
        expectError(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded");
        expectError(HttpStatus.INTERNAL_SERVER_ERROR, "application_error");
        expectError(HttpStatus.CONFLICT, "concurrent_idempotent_requests");

        for (String name : new String[] {"rate_limit_exceeded", "application_error", "concurrent_idempotent_requests"}) {
            assertThatThrownBy(() -> sender.send(MESSAGE))
                    .isInstanceOfSatisfying(EmailSendException.class, failure -> {
                        assertThat(failure.isRetryable()).isTrue();
                        assertThat(failure.getErrorName()).isEqualTo(name);
                    });
        }
    }

    @Test
    void authValidationAndChangedRetriesAreNotRetryable() {
        expectError(HttpStatus.UNAUTHORIZED, "missing_api_key");
        expectError(HttpStatus.FORBIDDEN, "validation_error");
        expectError(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_parameter");
        expectError(HttpStatus.CONFLICT, "invalid_idempotent_request");

        for (String name : new String[] {"missing_api_key", "validation_error", "invalid_parameter",
                "invalid_idempotent_request"}) {
            assertThatThrownBy(() -> sender.send(MESSAGE))
                    .isInstanceOfSatisfying(EmailSendException.class, failure -> {
                        assertThat(failure.isRetryable()).isFalse();
                        assertThat(failure.getErrorName()).isEqualTo(name);
                    });
        }
    }

    @Test
    void timeoutsAreRetryable() {
        server.expect(requestTo("https://api.resend.com/emails"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> sender.send(MESSAGE))
                .isInstanceOfSatisfying(EmailSendException.class, failure -> {
                    assertThat(failure.isRetryable()).isTrue();
                    assertThat(failure.getMessage()).isEqualTo("Network error: SocketTimeoutException");
                });
    }

    @Test
    void storedErrorNeverRepeatsTheProvidersMessage() {
        server.expect(requestTo("https://api.resend.com/emails")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"statusCode\":422,\"name\":\"validation_error\",\"message\":\"Invalid `to`: student@example.com\"}"));

        assertThatThrownBy(() -> sender.send(MESSAGE))
                .hasMessage("HTTP 422 validation_error")
                .hasMessageNotContaining("student@example.com");
    }

    @Test
    void withoutCredentialsItIsNotConfiguredAndSendsNothing() {
        ResendEmailSender unconfigured = new ResendEmailSender(RestClient.create(), "", "");

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThat(new ResendEmailSender(RestClient.create(), "re_key", " ").isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfigured.send(MESSAGE)).isInstanceOf(EmailSendException.class);
    }

    private void expectError(HttpStatus status, String name) {
        server.expect(requestTo("https://api.resend.com/emails")).andRespond(withStatus(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"statusCode\":" + status.value() + ",\"name\":\"" + name + "\",\"message\":\"x\"}"));
    }
}
