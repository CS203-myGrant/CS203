package mygrant.email;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import mygrant.auth.JwtAuthenticationFilter;
import mygrant.auth.JwtService;
import mygrant.auth.SecurityConfig;
import mygrant.user.UserRepository;

/** The unsubscribe page is public and authorised only by the signed token in the link. */
@WebMvcTest(UnsubscribeController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, EmailConfig.class, UnsubscribeTokens.class})
@TestPropertySource(properties = "jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class UnsubscribeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UnsubscribeTokens tokens;

    @MockBean
    private EmailPreferenceService preferenceService;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private UserRepository userRepository;

    @Test
    void openingTheLinkOnlyShowsAButtonAndChangesNothing() throws Exception {
        mockMvc.perform(get("/api/v1/email/unsubscribe").param("token", tokens.issue(7L)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("<form method=\"post\" action=\"/api/v1/email/unsubscribe\">")))
                .andExpect(content().string(containsString("value=\"" + tokens.issue(7L) + "\"")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cache-Control", containsString("no-store")));

        verifyNoInteractions(preferenceService);
    }

    @Test
    void pressingTheButtonUnsubscribesOnlyTheTokensAccount() throws Exception {
        mockMvc.perform(post("/api/v1/email/unsubscribe")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", tokens.issue(7L)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("You are unsubscribed")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));

        verify(preferenceService).unsubscribe(7L);
        verify(preferenceService, times(1)).unsubscribe(anyLong());
    }

    @Test
    void mailClientOneClickPostIsAccepted() throws Exception {
        // RFC 8058: the client POSTs to the List-Unsubscribe URL with this fixed form body.
        mockMvc.perform(post("/api/v1/email/unsubscribe?token=" + tokens.issue(7L))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("List-Unsubscribe=One-Click"))
                .andExpect(status().isOk());

        verify(preferenceService).unsubscribe(7L);
    }

    @Test
    void repeatedPostsAreHarmless() throws Exception {
        String token = tokens.issue(7L);
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/email/unsubscribe").param("token", token))
                    .andExpect(status().isOk());
        }

        verify(preferenceService, times(2)).unsubscribe(7L);
    }

    @Test
    void badTokensGetTheSameGeneric400AndChangeNothing() throws Exception {
        String valid = tokens.issue(7L);
        String tampered = "8" + valid.substring(1);
        String otherSecret = new UnsubscribeTokens("c29tZS1vdGhlci1zZWNyZXQtdmFsdWUtZm9yLXRlc3Rz").issue(7L);

        for (String token : new String[] {tampered, otherSecret, "7.not-a-signature", ""}) {
            mockMvc.perform(post("/api/v1/email/unsubscribe").param("token", token))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(containsString("This link is not valid")))
                    .andExpect(content().string(not(containsString(token.isEmpty() ? "<never>" : token))));
            mockMvc.perform(get("/api/v1/email/unsubscribe").param("token", token))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(containsString("This link is not valid")));
        }
        mockMvc.perform(post("/api/v1/email/unsubscribe")).andExpect(status().isBadRequest());

        verify(preferenceService, never()).unsubscribe(anyLong());
    }
}
