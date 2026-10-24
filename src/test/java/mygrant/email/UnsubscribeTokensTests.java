package mygrant.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import mygrant.auth.JwtService;

class UnsubscribeTokensTests {

    private static final String SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final UnsubscribeTokens tokens = new UnsubscribeTokens(SECRET);

    @Test
    void issuedTokenVerifiesForTheUserItWasIssuedFor() {
        assertThat(tokens.verify(tokens.issue(7L))).contains(7L);
        assertThat(tokens.verify(tokens.issue(123456789L))).contains(123456789L);
    }

    @Test
    void tamperedSignatureIsRejected() {
        String token = tokens.issue(7L);
        char last = token.charAt(10);
        String tampered = token.substring(0, 10) + (last == 'A' ? 'B' : 'A') + token.substring(11);

        assertThat(tokens.verify(tampered)).isEmpty();
    }

    @Test
    void signatureForOneUserCannotBeReusedForAnother() {
        String signature = tokens.issue(7L).substring("7.".length());

        assertThat(tokens.verify("8." + signature)).isEmpty();
        assertThat(tokens.verify("07." + signature)).isEmpty();
        assertThat(tokens.verify(tokens.issue(8L))).contains(8L);
    }

    @Test
    void malformedTokensAreRejected() {
        String valid = tokens.issue(7L);

        assertThat(tokens.verify(null)).isEmpty();
        assertThat(tokens.verify("")).isEmpty();
        assertThat(tokens.verify("7")).isEmpty();
        assertThat(tokens.verify("7.")).isEmpty();
        assertThat(tokens.verify(valid + "x")).isEmpty();
        assertThat(tokens.verify(valid.substring(0, valid.length() - 1))).isEmpty();
        assertThat(tokens.verify(" " + valid)).isEmpty();
        assertThat(tokens.verify("99999999999999999999." + valid.substring(2))).isEmpty();
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        UnsubscribeTokens otherEnvironment = new UnsubscribeTokens("c29tZS1vdGhlci1zZWNyZXQtdmFsdWUtZm9yLXRlc3Rz");

        assertThat(tokens.verify(otherEnvironment.issue(7L))).isEmpty();
    }

    @Test
    void loginTokensAreNotUnsubscribeTokensAndViceVersa() {
        JwtService jwtService = new JwtService(SECRET, 900_000, 604_800_000);
        String unsubscribe = tokens.issue(7L);

        assertThat(tokens.verify(jwtService.generateToken("7"))).isEmpty();
        assertThat(jwtService.isTokenValid(unsubscribe, "7")).isFalse();
    }
}
