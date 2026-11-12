package mygrant.email;

import static mygrant.email.EmailTestData.critical;
import static mygrant.email.EmailTestData.informational;
import static mygrant.email.EmailTestData.unassessed;
import static mygrant.email.EmailTestData.verifiedUser;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import mygrant.impact.ImpactUrgency;
import mygrant.user.User;

class AlertEmailRendererTests {

    private static final UUID DELIVERY = UUID.fromString("6f1c2a8e-0000-4000-8000-000000000001");
    private static final Instant CREATED = Instant.parse("2026-10-24T14:05:00Z");

    private final UnsubscribeTokens tokens = new UnsubscribeTokens(EmailTestData.JWT_SECRET);
    private final AlertEmailRenderer renderer = new AlertEmailRenderer(EmailTestData.properties(), tokens);
    private final User user = verifiedUser(7L);

    @Test
    void immediateEmailHasThePlainSummaryAndALinkToTheAssessmentAndNothingElse() {
        ImpactEmailAlert alert = critical(42L, 7L, CREATED);

        EmailMessage message = render(alert);

        assertThat(message.to()).isEqualTo("student7@example.com");
        assertThat(message.subject()).isEqualTo("High impact: F-1 rule 42");
        assertThat(message.idempotencyKey()).isEqualTo(DELIVERY.toString());
        assertThat(message.html())
                .contains("New policy affecting F-1: Alert 42")
                .contains("F-1 rule 42")
                .contains("href=\"http://localhost:8080/mygrant-prototype.html#impact=142\"")
                .contains("View in myGRANT")
                .contains("24 Oct 2026, 10:05 AM EDT")
                .contains("critical alerts are set to arrive right away")
                .contains("not legal advice");
        assertThat(message.text())
                .contains("New policy affecting F-1: Alert 42")
                .contains("View in myGRANT: http://localhost:8080/mygrant-prototype.html#impact=142");
    }

    @Test
    void noFullAnalysisReachesTheEmail() {
        // Items carry only a title and the plain-English summary; there is nowhere for reasons,
        // actions or missing information to come from.
        assertThat(ImpactEmailAlert.class.getDeclaredFields()).extracting(java.lang.reflect.Field::getName)
                .doesNotContain("reasons", "analysis", "recommendedActions", "missingInformation");
    }

    @Test
    void anEscalationSaysTheChangeIsNowHighImpact() {
        ImpactEmailAlert alert = EmailTestData.alert(42L, 7L, ImpactUrgency.HIGH, ImpactEmailAlert.Kind.ESCALATION, CREATED);

        assertThat(render(alert).subject()).isEqualTo("Now high impact: F-1 rule 42");
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
        assertThat(message.subject()).isEqualTo("Medium impact: F-1 rule 42");
    }

    @Test
    void immediateEmailEscapesStoredText() {
        ImpactEmailAlert alert = critical(42L, 7L, CREATED);
        ReflectionTestUtils.setField(alert, "summary", "Rule <script>alert(1)</script> & \"quotes\"");

        EmailMessage message = render(alert);

        assertThat(message.html()).contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;quotes&quot;")
                .doesNotContain("<script>");
    }

    @Test
    void longTitlesAreCutAtAWordForTheSubject() {
        ImpactEmailAlert alert = critical(42L, 7L, CREATED);
        ReflectionTestUtils.setField(alert, "policyTitle", "Establishing a Fixed Time Period of Admission and an "
                + "Extension of Stay Procedure for Nonimmigrant Academic Students, Exchange Visitors, and "
                + "Representatives of Foreign Information Media");

        String subject = render(alert).subject();

        assertThat(subject).startsWith("High impact: Establishing").endsWith("…");
        assertThat(subject.length()).isLessThanOrEqualTo("High impact: ".length() + 91);
    }

    @Test
    void aRetryRendersExactlyTheSameEmail() {
        ImpactEmailAlert alert = critical(42L, 7L, CREATED);

        assertThat(render(alert)).isEqualTo(render(alert));
    }

    @Test
    void digestListsEachItemWithItsImpactSummaryAndLink() {
        List<ImpactEmailAlert> items = List.of(informational(41L, 7L, CREATED),
                EmailTestData.alert(43L, 7L, ImpactUrgency.LOW, ImpactEmailAlert.Kind.FIRST, CREATED.plusSeconds(3600)));

        EmailMessage message = renderDigest(items);

        assertThat(message.subject()).isEqualTo("Your myGRANT digest for 24 Oct 2026: 2 new alerts");
        assertThat(message.html())
                .contains("href=\"http://localhost:8080/mygrant-prototype.html#impact=141\"")
                .contains("href=\"http://localhost:8080/mygrant-prototype.html#impact=143\"")
                .contains("Medium impact").contains("Low impact")
                .contains("other alerts are set to the daily digest");
        assertThat(message.text())
                .contains("Medium impact - 24 Oct 2026, 10:05 AM EDT\nF-1 rule 41\nNew policy affecting F-1: Alert 41")
                .contains("View in myGRANT: http://localhost:8080/mygrant-prototype.html#impact=143");
        assertThat(message.headers()).containsKeys("List-Unsubscribe", "List-Unsubscribe-Post");
        assertThat(message.html().indexOf("Alert 41")).isLessThan(message.html().indexOf("Alert 43"));
    }

    @Test
    void aChangeWeCouldNotAssessSaysSoAndLinksToItsAlert() {
        EmailMessage message = renderDigest(List.of(unassessed(41L, 7L, CREATED)));

        assertThat(message.text())
                .contains("Not assessed yet")
                .contains("We couldn't assess this one yet. New policy affecting F-1: Alert 41")
                .contains("View in myGRANT: http://localhost:8080/mygrant-prototype.html#notification=241");
    }

    @Test
    void digestWithAHighImpactItemSaysBothSettingsSentIt() {
        EmailMessage message = renderDigest(List.of(critical(42L, 7L, CREATED)));

        assertThat(message.subject()).endsWith(": 1 new alert");
        assertThat(message.html()).contains("High impact").contains("critical alerts are set to the daily digest");
        assertThat(renderDigest(List.of(critical(42L, 7L, CREATED), informational(43L, 7L, CREATED))).text())
                .contains("critical and other alerts are set to the daily digest");
    }

    @Test
    void digestEscapesStoredTextAndRendersTheSameEachTime() {
        ImpactEmailAlert alert = informational(41L, 7L, CREATED);
        ReflectionTestUtils.setField(alert, "summary", "<b>Fee</b> & more");

        EmailMessage message = renderDigest(List.of(alert));

        assertThat(message.html()).contains("&lt;b&gt;Fee&lt;/b&gt; &amp; more").doesNotContain("<b>Fee</b>");
        assertThat(renderDigest(List.of(alert))).isEqualTo(message);
    }

    private EmailMessage renderDigest(List<ImpactEmailAlert> items) {
        return renderer.render(EmailTestData.claimed(DELIVERY, 7L, DeliveryKind.DIGEST,
                java.time.LocalDate.parse("2026-10-24"), 1), user, items);
    }

    private EmailMessage render(ImpactEmailAlert alert) {
        return renderer.render(EmailTestData.claimed(DELIVERY, 7L, DeliveryKind.IMMEDIATE, null, 1), user,
                List.of(alert));
    }
}
