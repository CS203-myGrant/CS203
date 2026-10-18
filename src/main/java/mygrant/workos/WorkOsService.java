package mygrant.workos;

import java.net.URI;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;

import mygrant.user.dto.WorkOsUser;

@Service
public class WorkOsService {

    private final RestClient client;
    private final String apiKey;
    private final String clientId;
    private final String redirectUri;

    public WorkOsService(
            @Value("${WORKOS_API_KEY:}") String apiKey,
            @Value("${WORKOS_CLIENT_ID:}") String clientId,
            @Value("${WORKOS_REDIRECT_URI:}") String redirectUri) {
        this.client = RestClient.builder().baseUrl("https://api.workos.com").build();
        this.apiKey = apiKey;
        this.clientId = clientId;
        this.redirectUri = redirectUri;
    }

    public URI authorizationUri(String state) {
        ensureConfigured();
        return UriComponentsBuilder.fromUriString("https://api.workos.com/user_management/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("provider", "authkit")
                .queryParam("state", state)
                .build().encode().toUri();
    }

    public WorkOsUser authenticate(String code) {
        ensureConfigured();
        JsonNode response = client.post()
                .uri("/user_management/authenticate")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .body(Map.of(
                        "client_id", clientId,
                        "client_secret", apiKey,
                        "grant_type", "authorization_code",
                        "code", code))
                .retrieve()
                .body(JsonNode.class);

        JsonNode user = response.path("user");
        WorkOsUser workOsUser = new WorkOsUser(
                user.path("id").asText(),
                user.path("email").asText(),
                user.path("first_name").asText(""),
                user.path("last_name").asText(""),
                user.path("email_verified").asBoolean(false));
        if (!workOsUser.emailVerified()) {
            throw new IllegalStateException("WorkOS returned an unverified email");
        }
        return workOsUser;
    }

    private void ensureConfigured() {
        if (apiKey.isBlank() || clientId.isBlank() || redirectUri.isBlank()) {
            throw new IllegalStateException("WorkOS is not configured");
        }
    }
}
