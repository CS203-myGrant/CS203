package mygrant.actions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns one recommended action into an item's key, title and description. V30's backfill repeats
 * these rules in SQL and ActionItemBackfillParityTest keeps the two in step. Whitespace means the
 * six ASCII characters only, because Java's {@code \s}, {@code String.trim()} and PostgreSQL's
 * {@code \s} each treat other characters differently.
 */
final class ActionItemText {

    static final int TITLE_LIMIT = 120;
    private static final int TRUNCATED_LENGTH = 117;
    private static final String WHITESPACE = "[ \\t\\n\\x0B\\f\\r]+";
    private static final Pattern RUNS = Pattern.compile(WHITESPACE);
    private static final Pattern EDGES = Pattern.compile("^" + WHITESPACE + "|" + WHITESPACE + "$");

    private ActionItemText() {
    }

    static String trim(String action) {
        return EDGES.matcher(action).replaceAll("");
    }

    /** SHA-256 hex of the trimmed text with whitespace runs collapsed to one space, lower-cased. */
    static String key(String action) {
        String normalized = RUNS.matcher(trim(action)).replaceAll(" ").toLowerCase(Locale.ROOT);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Lengths count characters, not UTF-16 units, as PostgreSQL does, so an emoji is never split. */
    static String title(String action) {
        String trimmed = trim(action);
        if (!truncated(trimmed)) {
            return trimmed;
        }
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, TRUNCATED_LENGTH)) + "…";
    }

    /** The full text when the title had to be shortened; null otherwise. */
    static String description(String action) {
        String trimmed = trim(action);
        return truncated(trimmed) ? trimmed : null;
    }

    private static boolean truncated(String trimmed) {
        return trimmed.codePointCount(0, trimmed.length()) > TITLE_LIMIT;
    }
}
