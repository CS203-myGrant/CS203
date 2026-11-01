package mygrant.actions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ActionItemTextTest {

    @Test
    void keyIgnoresCaseAndRunsOfAsciiWhitespace() {
        // Lower-case hex SHA-256 of "file form i-765", as printf 'file form i-765' | shasum -a 256 gives.
        assertThat(ActionItemText.key("  File\t\tForm  I-765\r\n"))
                .isEqualTo("278097b07a8aa3a0dda734f5bc829b6a84f488c65fb884e105c1190747a11c87");
        assertThat(ActionItemText.key("Renew \u000Byour I-20"))
                .isEqualTo("fa04bda53342ef89d92a3bbafb497a9d6c7749a6b3f3fe59539f4df265e0ede5");
    }

    @Test
    void otherSpacesAreNotWhitespace() {
        assertThat(ActionItemText.key("Upload scan")).isNotEqualTo(ActionItemText.key("Upload scan"));
        assertThat(ActionItemText.trim("　Upload　")).isEqualTo("　Upload　");
    }

    @Test
    void shortActionsAreTheirOwnTitleWithoutDescription() {
        String exactlyLimit = "x".repeat(ActionItemText.TITLE_LIMIT);
        assertThat(ActionItemText.title("  Renew   your I-20 \n")).isEqualTo("Renew   your I-20");
        assertThat(ActionItemText.description("Renew your I-20")).isNull();
        assertThat(ActionItemText.title(exactlyLimit)).isEqualTo(exactlyLimit);
        assertThat(ActionItemText.description(exactlyLimit)).isNull();
    }

    @Test
    void longActionsKeep117CharactersAndAnEllipsis() {
        String action = " " + "y".repeat(121) + " ";
        assertThat(ActionItemText.title(action)).isEqualTo("y".repeat(117) + "…");
        assertThat(ActionItemText.description(action)).isEqualTo("y".repeat(121));
    }

    @Test
    void charactersOutsideTheBasicPlaneCountOnceAndAreNeverSplit() {
        String emoji = "z".repeat(116) + "😀".repeat(5);
        assertThat(emoji.codePointCount(0, emoji.length())).isEqualTo(121);
        assertThat(ActionItemText.title(emoji)).isEqualTo("z".repeat(116) + "😀…");
        String fits = "z".repeat(115) + "😀".repeat(5);
        assertThat(ActionItemText.title(fits)).isEqualTo(fits);
    }
}
