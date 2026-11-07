package mygrant.rules;

import java.util.Locale;
import java.util.Objects;

import io.swagger.v3.oas.annotations.media.Schema;

/** One docket, RIN or linked Federal Register document number, upper-cased and trimmed. */
@Schema(name = "RuleIdentifier", description = "A docket (ICEB-2025-0001) or RIN (1653-AA95)")
public record RuleIdentifier(IdentifierKind kind, @Schema(example = "ICEB-2025-0001") String value) {

    public RuleIdentifier {
        Objects.requireNonNull(kind, "kind");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Rule identifier value must not be blank");
        }
        value = value.trim().toUpperCase(Locale.ROOT);
    }

    public static RuleIdentifier docket(String value) {
        return new RuleIdentifier(IdentifierKind.DOCKET, value);
    }

    public static RuleIdentifier rin(String value) {
        return new RuleIdentifier(IdentifierKind.RIN, value);
    }

    public static RuleIdentifier federalRegisterDocument(String value) {
        return new RuleIdentifier(IdentifierKind.FR_DOCUMENT, value);
    }

    @Override
    public String toString() {
        return kind + " " + value;
    }
}
