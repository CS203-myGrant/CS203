package mygrant.email;

import static mygrant.email.EmailTestData.critical;
import static mygrant.email.EmailTestData.informational;
import static mygrant.email.EmailTestData.verifiedUser;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import mygrant.notifications.Notification;
import mygrant.user.User;

class AlertEmailRendererTests {

    private static final UUID DELIVERY = UUID.fromString("6f1c2a8e-0000-4000-8000-000000000001");
    private static final Instant CREATED = Instant.parse("2026-10-24T14:05:00Z");

    private final UnsubscribeTokens tokens = new UnsubscribeTokens(EmailTestData.JWT_SECRET);
    private final AlertEmailRenderer renderer = new AlertEmailRenderer(EmailTestData.properties(), tokens);
    private final User user = verifiedUser(7L);

    @Test
    void immediateEmailHasTheSummaryALinkAndNothingElse() {
        Notification notification = critical(42L, 7L, CREATED);

        EmailMessage message = render(notification);

        assertThat(message.to()).isEqualTo("student7@example.com");
        assertThat(message.subject()).isEqualTo("Action needed: New policy affecting F-1: Alert 42");
        assertThat(message.idempotencyKey()).isEqualTo(DELIVERY.toString());
        assertThat(message.html())
                .contains("New policy affecting F-1: Alert 42")
                .contains("href=\"http://localhost:8080/mygrant-prototype.html#notification=42\"")
                .contains("View in myGRANT")
                .contains("24 Oct 2026, 10:05 AM EDT")
                .contains("critical alerts are set to arrive right away");
        assertThat(message.text())
                .contains("New policy affecting F-1: Alert 42")
                .contains("View in myGRANT: http://localhost:8080/mygrant-prototype.html#notification=42");
    }

    @Test
    void everyEmailCarriesAnUnsubscribeLinkAndOneClickHeaders() {
        EmailMessage message = render(informational(42L, 7L, CREATED));
        String unsubscribe = "http://localhost:8080/api/v1/email/unsubscribe?token=" + tokens.issue(7L);

        assertThat(message.html()).contains("href=\"" + unsubscribe + "\"").contains("Manage email settings");
        assertThat(message.text()).contains("Unsubscribe: " + unsubscribe)
                .contains("Manage email settings: http://localhost:8080/mygrant-prototype.html#view=profile");
        assertThat(message.headers())
                .containsEntry("List-Unsubscribe", "<" + unsubscribe + ">")
                .containsEntry("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        assertThat(message.subject()).startsWith("Policy alert: ");
    }

    @Test
    void escapesPolicyTextInTheHtmlPart() {
        Notification notification = critical(42L, 7L, CREATED);
        ReflectionTestUtils.setField(notification, "message", "Rule <script>alert(1)</script> & \"quotes\"");

        EmailMessage message = render(notification);

        assertThat(message.html()).doesNotContain("<script>")
                .contains("Rule &lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;quotes&quot;");
        assertThat(message.text()).contains("Rule <script>alert(1)</script> & \"quotes\"");
    }

    @Test
    void longOrMultiLineMessagesMakeAOneLineSubject() {
        Notification notification = critical(42L, 7L, CREATED);
        ReflectionTestUtils.setField(notification, "message",
                "New policy affecting F-1, J-1:\r\nFixed Time Period of Admission and Extension of Stay Procedures "
                        + "for Nonimmigrant Academic Students (effective 2026-10-01). Action needed: review your dates.");

        String subject = render(notification).subject();

        assertThat(subject).doesNotContain("\r").doesNotContain("\n").endsWith("…");
        assertThat(subject.length()).isLessThanOrEqualTo("Action needed: ".length() + 91);
    }

    @Test
    void renderingTheSameDeliveryTwiceGivesTheSameRequest() {
        // A retry reuses the idempotency key, and Resend refuses a key reused with other contents.
        Notification notification = critical(42L, 7L, CREATED);

        assertThat(render(notification)).isEqualTo(render(notification));
    }

    @Test
    void digestListsEachAlertWithItsDateAndLink() {
        List<Notification> items = List.of(informational(41L, 7L, CREATED), informational(43L, 7L, CREATED.plusSeconds(3600)));

        EmailMessage message = renderDigest(items);

        assertThat(message.subject()).isEqualTo("Your myGRANT digest for 24 Oct 2026: 2 new alerts");
        assertThat(message.idempotencyKey()).isEqualTo(DELIVERY.toString());
        assertThat(message.html())
                .contains("2 new alerts for you.")
                .contains("New policy affecting F-1: Alert 41", "New policy affecting F-1: Alert 43")
                .contains("href=\"http://localhost:8080/mygrant-prototype.html#notification=41\"")
                .contains("href=\"http://localhost:8080/mygrant-prototype.html#notification=43\"")
                .contains("24 Oct 2026, 10:05 AM EDT", "24 Oct 2026, 11:05 AM EDT")
                .contains("other alerts are set to the daily digest");
        assertThat(message.text())
                .contains("For information - 24 Oct 2026, 10:05 AM EDT\nNew policy affecting F-1: Alert 41")
                .contains("View in myGRANT: http://localhost:8080/mygrant-prototype.html#notification=43");
        assertThat(message.headers()).containsKeys("List-Unsubscribe", "List-Unsubscribe-Post");
        assertThat(message.html().indexOf("Alert 41")).isLessThan(message.html().indexOf("Alert 43"));
    }

    @Test
    void digestWithACriticalAlertSaysBothSettingsSentIt() {
        EmailMessage message = renderDigest(List.of(critical(42L, 7L, CREATED)));

        assertThat(message.subject()).endsWith(": 1 new alert");
        assertThat(message.html()).contains("Action needed").contains("critical alerts are set to the daily digest");
        assertThat(renderDigest(List.of(critical(42L, 7L, CREATED), informational(43L, 7L, CREATED))).text())
                .contains("critical and other alerts are set to the daily digest");
    }

    @Test
    void digestEscapesPolicyTextAndRendersTheSameEachTime() {
        Notification notification = informational(41L, 7L, CREATED);
        ReflectionTestUtils.setField(notification, "message", "<b>Fee</b> & more");

        EmailMessage message = renderDigest(List.of(notification));

        assertThat(message.html()).contains("&lt;b&gt;Fee&lt;/b&gt; &amp; more").doesNotContain("<b>Fee</b>");
        assertThat(renderDigest(List.of(notification))).isEqualTo(message);
    }

    private EmailMessage renderDigest(List<Notification> items) {
        return renderer.render(EmailTestData.claimed(DELIVERY, 7L, DeliveryKind.DIGEST,
                java.time.LocalDate.parse("2026-10-24"), 1), user, items);
    }

    private EmailMessage render(Notification notification) {
        return renderer.render(EmailTestData.claimed(DELIVERY, 7L, DeliveryKind.IMMEDIATE, null, 1), user,
                List.of(notification));
    }
}
