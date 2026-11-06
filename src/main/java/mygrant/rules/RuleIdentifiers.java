package mygrant.rules;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the docket numbers and RINs that tie the documents of one rulemaking together. Federal
 * Register imports pass the API's {@code docket_ids} and {@code regulation_id_numbers}; the
 * bracketed header of every document is parsed as well, so saved fixtures and documents stored
 * before CS203-86 link the same way offline.
 */
public final class RuleIdentifiers {

    /**
     * Only the header is read: everything before "SUMMARY:". The body of a long rule cites other
     * rulemakings by docket and RIN, and those must not link it to them.
     */
    private static final int MAX_HEADER_CHARACTERS = 20_000;
    private static final Pattern RIN = Pattern.compile("\\b\\d{4}-[A-Z]{2}\\d{2}\\b");
    private static final Pattern RIN_LINE = Pattern.compile("(?m)^[ \\t]*RINs?\\b.*$");
    private static final Pattern BRACKETED = Pattern.compile("\\[([^\\[\\]\\r\\n]{1,300})]");
    private static final Pattern DOCKET_LABEL =
            Pattern.compile("(?i)\\bdocket\\s+(?:nos?\\.?|numbers?|ids?)?\\s*:?\\s*");
    private static final Pattern HYPHENATED = Pattern.compile("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)+");
    /** A bare regulations.gov docket id: ICEB-2025-0001, USCIS-2023-0005, FDA-2019-N-1234. */
    private static final Pattern REGULATIONS_GOV_DOCKET =
            Pattern.compile("[A-Z][A-Z0-9]*-\\d{4}-(?:[A-Z]{1,3}-)?\\d{3,}");

    private RuleIdentifiers() {
    }

    /** Dockets from "[DHS Docket No. ICEB-2025-0001]" and RINs from "RIN 1653-AA95" lines in the header. */
    public static Set<RuleIdentifier> fromHeader(String content) {
        Set<RuleIdentifier> found = new LinkedHashSet<>();
        if (content == null || content.isBlank()) {
            return found;
        }
        String header = header(content);
        Matcher bracketed = BRACKETED.matcher(header);
        while (bracketed.find()) {
            docketsIn(bracketed.group(1)).forEach(docket -> found.add(RuleIdentifier.docket(docket)));
        }
        Matcher lines = RIN_LINE.matcher(header);
        while (lines.find()) {
            Matcher rin = RIN.matcher(lines.group());
            while (rin.find()) {
                found.add(RuleIdentifier.rin(rin.group()));
            }
        }
        return found;
    }

    /**
     * The Federal Register API's identifiers. A docket comes as "DHS Docket No. ICEB-2025-0001", so
     * the label is dropped. Entries that are not dockets, such as "CIS No. 2741-23", are ignored.
     */
    public static Set<RuleIdentifier> fromFederalRegisterApi(Collection<String> docketIds,
            Collection<String> regulationIdNumbers) {
        Set<RuleIdentifier> found = new LinkedHashSet<>();
        for (String docketId : docketIds == null ? List.<String>of() : docketIds) {
            if (docketId == null) {
                continue;
            }
            List<String> labelled = docketsIn(docketId);
            if (!labelled.isEmpty()) {
                labelled.forEach(docket -> found.add(RuleIdentifier.docket(docket)));
            } else if (REGULATIONS_GOV_DOCKET.matcher(docketId.trim().toUpperCase(Locale.ROOT)).matches()) {
                found.add(RuleIdentifier.docket(docketId));
            }
        }
        for (String rin : regulationIdNumbers == null ? List.<String>of() : regulationIdNumbers) {
            if (rin == null) {
                continue;
            }
            Matcher matcher = RIN.matcher(rin.trim().toUpperCase(Locale.ROOT));
            while (matcher.find()) {
                found.add(RuleIdentifier.rin(matcher.group()));
            }
        }
        return found;
    }

    /** Every docket after a "Docket No." label, up to the next ";": "Docket Nos. A-2025-1 and A-2025-2". */
    static List<String> docketsIn(String text) {
        List<String> dockets = new java.util.ArrayList<>();
        Matcher label = DOCKET_LABEL.matcher(text);
        while (label.find()) {
            int end = text.indexOf(';', label.end());
            String rest = text.substring(label.end(), end < 0 ? text.length() : end);
            Matcher token = HYPHENATED.matcher(rest);
            while (token.find()) {
                if (token.group().chars().anyMatch(Character::isDigit)) {
                    dockets.add(token.group().toUpperCase(Locale.ROOT));
                }
            }
        }
        return dockets;
    }

    private static String header(String content) {
        int summary = content.indexOf("SUMMARY:");
        int end = summary < 0 ? content.length() : summary;
        return content.substring(0, Math.min(end, MAX_HEADER_CHARACTERS));
    }
}
