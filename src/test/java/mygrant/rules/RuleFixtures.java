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
 * and the 2026 final rule, plus the same-titled 2020 proposal on another docket. Synthetic
 * fixtures are named synthetic-* and say so in their first line.
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
    static final List<String> IDENTIFIERS = List.of("ICEB-2025-0001", "1653-AA95", "ICEB-2019-0006", "1653-AA78");

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
    }
}
