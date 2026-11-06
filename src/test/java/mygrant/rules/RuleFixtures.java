package mygrant.rules;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import mygrant.ingestion.dto.IngestPolicyRequest;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;

/**
 * The real Federal Register pair (DHS Docket ICEB-2025-0001, RIN 1653-AA95): the 2025 proposal
 * and the 2026 final rule, plus the same-titled 2020 proposal on another docket. Also the real
 * 2023 H-1B proposal (DHS Docket USCIS-2023-0005, RIN 1615-AC70), which DHS finalised as two
 * separate final rules, and the 2020 H-1B registration rulemaking (RIN 1615-AC61): proposal, final
 * rule, a delay notice whose new date is only in its DATES text, and the withdrawal. Synthetic
 * fixtures are named synthetic-* and say so in their first line.
 * The real texts are the documents' raw_text_url downloads, with the NUL bytes removed as
 * FederalRegisterClient does.
 */
final class RuleFixtures {

    static final String TITLE = "Establishing a Fixed Time Period of Admission and an Extension of Stay "
            + "Procedure for Nonimmigrant Academic Students, Exchange Visitors, and Representatives of "
            + "Foreign Information Media";
    static final String PROPOSAL = "2025-16554";
    static final String FINAL_RULE = "2026-14439";
    static final String OLD_PROPOSAL = "2020-20845";
    static final LocalDate PROPOSAL_PUBLISHED = LocalDate.of(2025, 8, 28);
    static final LocalDate FINAL_PUBLISHED = LocalDate.of(2026, 7, 17);
    static final LocalDate FINAL_EFFECTIVE = LocalDate.of(2026, 9, 15);
    static final LocalDate OLD_PROPOSAL_PUBLISHED = LocalDate.of(2020, 9, 25);
    /**
     * Synthetic: a delay notice moving the final rule's effective date from 15 Sep to 1 Nov 2026.
     * Like a real one, it gives the new date only in DATES; its effective_on is its own publication date.
     */
    static final String DATE_MOVE = "2026-99950";
    static final LocalDate DATE_MOVE_PUBLISHED = LocalDate.of(2026, 9, 1);
    static final LocalDate MOVED_EFFECTIVE = LocalDate.of(2026, 11, 1);
    /** Synthetic: news items about the rule, one reporting an injunction. */
    static final String NEWS_FEED = "newsrss/synthetic-rule-coverage-feed.xml";
    static final String INJUNCTION_HEADLINE = "Judge blocks rule ending open-ended stays for F-1 students";
    /** Synthetic: news of an appeals court lifting that injunction, published 3 Nov 2026. */
    static final String LIFT_FEED = "newsrss/synthetic-lift-feed.xml";
    static final String LIFT_HEADLINE = "Appeals court lifts injunction on rule ending open-ended stays for F-1 students";
    static final LocalDate LIFT_NEWS_PUBLISHED = LocalDate.of(2026, 11, 3);
    static final List<String> NEWS_HEADLINES = List.of(INJUNCTION_HEADLINE,
            "What the new admission rule means for F-1 students", "Universities sue over F-1 student admission limits",
            LIFT_HEADLINE);
    static final List<String> IDENTIFIERS = List.of("ICEB-2025-0001", "1653-AA95", "ICEB-2019-0006", "1653-AA78",
            "USCIS-2023-0005", "1615-AC70", "USCIS-2020-0019", "1615-AC61");

    static final String H1B_TITLE = "Modernizing H-1B Requirements, Providing Flexibility in the F-1 Program, and "
            + "Program Improvements Affecting Other Nonimmigrant Workers";
    static final String H1B_REGISTRATION_TITLE = "Improving the H-1B Registration Selection Process and Program Integrity";
    static final String H1B_PROPOSAL = "2023-23381";
    static final LocalDate H1B_PROPOSAL_PUBLISHED = LocalDate.of(2023, 10, 23);
    /** The first final rule: the registration provisions. */
    static final String H1B_REGISTRATION_RULE = "2024-01770";
    static final LocalDate H1B_REGISTRATION_PUBLISHED = LocalDate.of(2024, 2, 2);
    static final LocalDate H1B_REGISTRATION_EFFECTIVE = LocalDate.of(2024, 3, 4);
    /** The second final rule: the rest of the proposal, with its own effective date. */
    static final String H1B_FINAL_RULE = "2024-29354";
    static final LocalDate H1B_FINAL_PUBLISHED = LocalDate.of(2024, 12, 18);
    static final LocalDate H1B_FINAL_EFFECTIVE = LocalDate.of(2025, 1, 17);

    /** RIN 1615-AC61, DHS Docket USCIS-2020-0019: proposed, final, delayed, then withdrawn. */
    static final String AC61_TITLE = "Modification of Registration Requirement for Petitioners Seeking To File "
            + "Cap-Subject H-1B Petitions";
    static final String AC61_DELAY_TITLE = AC61_TITLE + "; Delay of Effective Date";
    static final String AC61_WITHDRAWAL_TITLE = AC61_TITLE + ", Implementation of Vacatur";
    static final String AC61_PROPOSAL = "2020-24259";
    static final String AC61_FINAL_RULE = "2021-00183";
    /** Real delay notice. The API's effective_on is 2021-01-08; DATES says "is delayed to December 31, 2021". */
    static final String AC61_DELAY = "2021-02665";
    static final String AC61_WITHDRAWAL = "2021-27714";

    /**
     * The real court order that postponed the D/S final rule (2026-14439) the day before it was due
     * to take effect. No Federal Register document records it, so an admin posts it as a secondary
     * source linked to the rule. {@link #COURT_ORDER_REQUEST} is the request body the README's curl
     * example sends; its summary is our own wording.
     */
    static final String COURT_ORDER = "d-mass-1-26-cv-13799-2026-09-14";
    static final String COURT_ORDER_TITLE = "Federal court postpones the duration of status final rule";
    static final String COURT = "U.S. District Court for the District of Massachusetts";
    static final LocalDate COURT_ORDER_PUBLISHED = LocalDate.of(2026, 9, 14);
    static final String COURT_ORDER_URL =
            "https://www.bu.edu/isso/2026/09/15/federal-ruling-postpones-implementation-of-dhs-rule-to-eliminate-d-s/";
    static final String COURT_ORDER_REQUEST = "court-orders/" + COURT_ORDER + ".json";

    private RuleFixtures() {
    }

    static IngestPolicyRequest proposal() {
        return federalRegister(PROPOSAL, PolicyStatus.PROPOSED, PROPOSAL_PUBLISHED, null);
    }

    static IngestPolicyRequest finalRule() {
        return federalRegister(FINAL_RULE, PolicyStatus.FINAL, FINAL_PUBLISHED, FINAL_EFFECTIVE);
    }

    static IngestPolicyRequest oldProposal() {
        return federalRegister(OLD_PROPOSAL, PolicyStatus.PROPOSED, OLD_PROPOSAL_PUBLISHED, null);
    }

    static IngestPolicyRequest dateMove() {
        return new IngestPolicyRequest(DATE_MOVE, TITLE + "; Delay of Effective Date", "Homeland Security Department",
                "F-1,J-1", PolicyStatus.FINAL, SourceType.PRIMARY, DATE_MOVE_PUBLISHED, DATE_MOVE_PUBLISHED,
                "https://www.federalregister.gov/d/" + DATE_MOVE, text("federal-register/synthetic-2026-99950-delay.txt"));
    }

    /** One of the three real H-1B documents, as stored by a Federal Register import. */
    static IngestPolicyRequest h1b(String documentNumber, String title, PolicyStatus status, LocalDate published,
            LocalDate effective) {
        return new IngestPolicyRequest(documentNumber, title, "Homeland Security Department", "H-1B", status,
                SourceType.PRIMARY, published, effective, "https://www.federalregister.gov/d/" + documentNumber,
                text("federal-register/" + documentNumber + ".txt"));
    }

    static IngestPolicyRequest federalRegister(String documentNumber, PolicyStatus status, LocalDate published,
            LocalDate effective) {
        return new IngestPolicyRequest(documentNumber, TITLE, "Homeland Security Department", "F-1,J-1", status,
                SourceType.PRIMARY, published, effective, "https://www.federalregister.gov/d/" + documentNumber,
                text("federal-register/" + documentNumber + ".txt"));
    }

    static String text(String resource) {
        try (InputStream in = RuleFixtures.class.getResourceAsStream("/" + resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing test fixture " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Makes the fixtures' document numbers and identifiers unused, inside a test transaction that
     * rolls back, so a local database that already holds these documents is left as it was.
     */
    static void isolate(NamedParameterJdbcTemplate jdbc, List<String> externalIds) {
        jdbc.update("""
                DELETE FROM policy_rules r WHERE EXISTS (
                    SELECT 1 FROM policy_rule_documents d
                    JOIN policy_document_identifiers i ON i.policy_id = d.policy_id
                    WHERE d.rule_id = r.id AND i.value IN (:identifiers))
                """, Map.of("identifiers", IDENTIFIERS));
        jdbc.update("DELETE FROM policy_document_identifiers WHERE value IN (:identifiers)",
                Map.of("identifiers", IDENTIFIERS));
        jdbc.update("DELETE FROM policy_documents WHERE external_id IN (:ids)", Map.of("ids", externalIds));
        jdbc.update("DELETE FROM policy_documents WHERE source_type = 'SECONDARY' AND title IN (:titles)",
                Map.of("titles", NEWS_HEADLINES));
    }
}
