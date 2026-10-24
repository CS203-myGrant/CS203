package mygrant.email;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import io.swagger.v3.oas.annotations.Hidden;

/**
 * The public page behind the unsubscribe link in every alert email. GET only shows a
 * button, because mail scanners prefetch links; POST, which is also what mail clients send
 * for List-Unsubscribe-Post one-click requests, turns the emails off. Both answer every bad
 * token with the same 400 page, so a token never reveals whether an account exists.
 * These are pages for people, not part of the JSON API, so they are left out of the API docs.
 */
@Hidden
@RestController
@RequestMapping("/api/v1/email/unsubscribe")
public class UnsubscribeController {

    private static final String INVALID = """
            <h1>This link is not valid</h1>
            <p>It may have been copied incompletely. You can turn off alert emails under
            <strong>Email alerts</strong> on your myGRANT profile.</p>""";

    private final UnsubscribeTokens tokens;
    private final EmailPreferenceService preferenceService;
    private final EmailProperties properties;

    public UnsubscribeController(UnsubscribeTokens tokens, EmailPreferenceService preferenceService,
            EmailProperties properties) {
        this.tokens = tokens;
        this.preferenceService = preferenceService;
        this.properties = properties;
    }

    @GetMapping
    public ResponseEntity<String> confirm(@RequestParam(required = false) String token) {
        if (tokens.verify(token).isEmpty()) {
            return page(HttpStatus.BAD_REQUEST, INVALID);
        }
        return page(HttpStatus.OK, """
                <h1>Stop myGRANT alert emails?</h1>
                <p>This turns off both immediate alerts and the daily digest. Alerts will still
                appear in the app.</p>
                <form method="post" action="/api/v1/email/unsubscribe">
                  <input type="hidden" name="token" value="%s">
                  <button type="submit">Unsubscribe</button>
                </form>""".formatted(HtmlUtils.htmlEscape(token)));
    }

    @PostMapping
    public ResponseEntity<String> unsubscribe(@RequestParam(required = false) String token) {
        Optional<Long> userId = tokens.verify(token);
        if (userId.isEmpty()) {
            return page(HttpStatus.BAD_REQUEST, INVALID);
        }
        preferenceService.unsubscribe(userId.get());
        return page(HttpStatus.OK, """
                <h1>You are unsubscribed</h1>
                <p>myGRANT will not email you policy alerts. They will still appear in the app,
                and you can turn emails back on in your <a href="%s">email settings</a>.</p>"""
                .formatted(HtmlUtils.htmlEscape(properties.settingsUrl())));
    }

    private ResponseEntity<String> page(HttpStatus status, String body) {
        return ResponseEntity.status(status)
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                // The token is in this page's URL; never pass it on to another site.
                .header("Referrer-Policy", "no-referrer")
                .header("Cache-Control", "no-store")
                .header("Content-Security-Policy",
                        "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
                .header("X-Robots-Tag", "noindex")
                .body("""
                        <!doctype html>
                        <html lang="en">
                        <head>
                        <meta charset="utf-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1">
                        <title>myGRANT email alerts</title>
                        <style>
                          body { font-family: system-ui, sans-serif; max-width: 32rem; margin: 4rem auto;
                                 padding: 0 1rem; line-height: 1.5; color: #1f2937; }
                          button { font: inherit; padding: .6rem 1.2rem; border: 0; border-radius: 6px;
                                   background: #1f2937; color: #fff; cursor: pointer; }
                        </style>
                        </head>
                        <body>
                        %s
                        </body>
                        </html>
                        """.formatted(body));
    }
}
