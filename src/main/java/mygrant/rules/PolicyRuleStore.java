package mygrant.rules;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import mygrant.common.Confidence;
import mygrant.ingestion.connector.ContentHash;
import mygrant.policies.PolicyStatus;
import mygrant.policies.SourceType;

/** JDBC access to policy rules, their documents and identifiers, and their stage history. */
@Repository
public class PolicyRuleStore {

    /**
     * Held for the rest of the transaction by whoever links a document, so two imports of the same
     * new rulemaking cannot each start a rule. Single-key advisory locks do not share a key space
     * with the two-key ones PolicyRecommendationStore takes.
     */
    private static final long LINKING_LOCK = 203_086L;

    private final NamedParameterJdbcTemplate jdbc;

    public PolicyRuleStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Rule(long id, String title, RuleState state, LocalDate primaryAsOf) {
    }

    public record Link(long policyId, long ruleId, MatchMethod method, Confidence confidence, String matchedOn,
            boolean needsReview, String reviewReason) {
    }

    /**
     * One history row to write. previous is null for a rule's first entry. aboutEffectiveDate says
     * whether the source is about the effective date ({@link DateNotice}).
     */
    public record NewHistoryEntry(long ruleId, RuleState previous, RuleState state, boolean applied,
            boolean alerted, boolean aboutEffectiveDate, RuleSource source, RecordedBy recordedBy) {
    }

    /** A stored history row. The previous values are what the rule held when the row was written. */
    public record HistoryRow(long id, long ruleId, RuleState state, RuleState previous, boolean applied,
            boolean alerted, boolean aboutEffectiveDate, RuleSource source, RecordedBy recordedBy,
            Instant recordedAt) {
    }

    void lockLinking() {
        jdbc.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(" + LINKING_LOCK + ")");
    }

    public Optional<Link> findLink(long policyId) {
        return jdbc.query("SELECT * FROM policy_rule_documents WHERE policy_id = :id",
                Map.of("id", policyId), (rs, row) -> link(rs)).stream().findFirst();
    }

    public Optional<Rule> findRule(long ruleId) {
        return jdbc.query("SELECT * FROM policy_rules WHERE id = :id", Map.of("id", ruleId),
                (rs, row) -> new Rule(rs.getLong("id"), rs.getString("title"),
                        new RuleState(PolicyStatus.valueOf(rs.getString("primary_stage")),
                                rs.getObject("effective_date", LocalDate.class), rs.getBoolean("disputed")),
                        rs.getObject("primary_as_of", LocalDate.class)))
                .stream().findFirst();
    }

    long createRule(String title, RuleState state, LocalDate primaryAsOf) {
        return jdbc.queryForObject("""
                INSERT INTO policy_rules (title, primary_stage, effective_date, disputed, primary_as_of)
                VALUES (:title, :stage, :effectiveDate, :disputed, :asOf)
                RETURNING id
                """, state(state).addValue("title", title).addValue("asOf", primaryAsOf, Types.DATE), Long.class);
    }

    void updateRule(long ruleId, RuleState state, LocalDate primaryAsOf) {
        jdbc.update("""
                UPDATE policy_rules SET primary_stage = :stage, effective_date = :effectiveDate,
                    disputed = :disputed, primary_as_of = :asOf, updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """, state(state).addValue("id", ruleId).addValue("asOf", primaryAsOf, Types.DATE));
    }

    /** Admin correction of a disputed effective date; preserves an auditable history entry. */
    public long applyAdminEffectiveDate(Rule before, RuleState after, RuleSource source, String actor, String reason) {
        updateRule(before.id(), after, before.primaryAsOf());
        MapSqlParameterSource p = state(after).addValue("ruleId", before.id()).addValue("previousStage", before.state().primaryStage().name())
                .addValue("previousDate", before.state().effectiveDate(), Types.DATE).addValue("previousDisputed", before.state().disputed())
                .addValue("sourceId", source.policyId(), Types.BIGINT).addValue("sourceExternalId", source.externalId()).addValue("sourceType", source.sourceType().name()).addValue("sourceUrl", source.url()).addValue("sourceTitle", source.title()).addValue("sourceAgency", source.agency()).addValue("observedOn", source.publishedOn(), Types.DATE).addValue("actor", actor).addValue("reason", truncate(reason,2000)).addValue("key", UUID.randomUUID().toString());
        return jdbc.queryForObject("INSERT INTO policy_rule_history (rule_id,primary_stage,effective_date,disputed,previous_primary_stage,previous_effective_date,previous_disputed,applied,alerted,about_effective_date,source_policy_id,source_external_id,source_type,source_url,source_title,source_agency,observed_on,recorded_by,actor_email,reason,dedupe_key) VALUES (:ruleId,:stage,:effectiveDate,:disputed,:previousStage,:previousDate,:previousDisputed,TRUE,FALSE,TRUE,:sourceId,:sourceExternalId,:sourceType,:sourceUrl,:sourceTitle,:sourceAgency,:observedOn,'ADMIN',:actor,:reason,:key) RETURNING id",p,Long.class);
    }

    void saveIdentifiers(long policyId, Collection<RuleIdentifier> identifiers) {
        if (identifiers.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO policy_document_identifiers (policy_id, kind, value) VALUES (:policyId, :kind, :value)
                ON CONFLICT DO NOTHING
                """, identifiers.stream().map(identifier -> new MapSqlParameterSource()
                        .addValue("policyId", policyId)
                        .addValue("kind", identifier.kind().name())
                        .addValue("value", identifier.value()))
                .toArray(MapSqlParameterSource[]::new));
    }

    public Set<RuleIdentifier> identifiersOf(long policyId) {
        return new LinkedHashSet<>(jdbc.query("""
                SELECT kind, value FROM policy_document_identifiers WHERE policy_id = :id ORDER BY kind, value
                """, Map.of("id", policyId),
                (rs, row) -> new RuleIdentifier(IdentifierKind.valueOf(rs.getString("kind")), rs.getString("value"))));
    }

    /**
     * Rules that hold any of the identifiers, each with the identifiers it matched. A rule's
     * identity comes from its primary documents only: a docket or RIN carried by linked news never
     * pulls another document in. A Federal Register document number matches the rule holding the
     * primary document with that number.
     */
    Map<Long, Set<RuleIdentifier>> rulesMatching(Collection<RuleIdentifier> identifiers) {
        Map<Long, Set<RuleIdentifier>> matches = new LinkedHashMap<>();
        List<String> keys = identifiers.stream()
                .filter(identifier -> identifier.kind() != IdentifierKind.FR_DOCUMENT)
                .map(identifier -> identifier.kind() + ":" + identifier.value())
                .toList();
        if (!keys.isEmpty()) {
            jdbc.query("""
                    SELECT DISTINCT d.rule_id, i.kind, i.value
                    FROM policy_document_identifiers i
                    JOIN policy_rule_documents d ON d.policy_id = i.policy_id
                    JOIN policy_documents p ON p.id = i.policy_id
                    WHERE p.source_type = 'PRIMARY' AND i.kind IN ('DOCKET', 'RIN')
                      AND (i.kind || ':' || i.value) IN (:keys)
                    ORDER BY d.rule_id
                    """, Map.of("keys", keys), rs -> {
                matches.computeIfAbsent(rs.getLong("rule_id"), id -> new LinkedHashSet<>())
                        .add(new RuleIdentifier(IdentifierKind.valueOf(rs.getString("kind")), rs.getString("value")));
            });
        }
        List<String> documentNumbers = identifiers.stream()
                .filter(identifier -> identifier.kind() == IdentifierKind.FR_DOCUMENT)
                .map(RuleIdentifier::value)
                .toList();
        if (!documentNumbers.isEmpty()) {
            jdbc.query("""
                    SELECT d.rule_id, upper(p.external_id) AS document_number
                    FROM policy_documents p JOIN policy_rule_documents d ON d.policy_id = p.id
                    WHERE p.source_type = 'PRIMARY' AND upper(p.external_id) IN (:numbers)
                    ORDER BY d.rule_id
                    """, Map.of("numbers", documentNumbers), rs -> {
                matches.computeIfAbsent(rs.getLong("rule_id"), id -> new LinkedHashSet<>())
                        .add(RuleIdentifier.federalRegisterDocument(rs.getString("document_number")));
            });
        }
        return matches;
    }

    /** A primary document's title and the rule it is in. */
    record TitleCandidate(long ruleId, String title) {
    }

    /** The title of every primary document in a rule, for the title fallback. */
    List<TitleCandidate> primaryTitles() {
        return jdbc.query("""
                SELECT d.rule_id, p.title FROM policy_rule_documents d JOIN policy_documents p ON p.id = d.policy_id
                WHERE p.source_type = 'PRIMARY' ORDER BY d.rule_id, p.id
                """, Map.of(), (rs, row) -> new TitleCandidate(rs.getLong("rule_id"), rs.getString("title")));
    }

    /** The dockets and RINs of each rule's primary documents. */
    Map<Long, Set<RuleIdentifier>> primaryIdentifiers(Collection<Long> ruleIds) {
        Map<Long, Set<RuleIdentifier>> identifiers = new LinkedHashMap<>();
        if (ruleIds.isEmpty()) {
            return identifiers;
        }
        jdbc.query("""
                SELECT DISTINCT d.rule_id, i.kind, i.value
                FROM policy_rule_documents d
                JOIN policy_documents p ON p.id = d.policy_id
                JOIN policy_document_identifiers i ON i.policy_id = d.policy_id
                WHERE d.rule_id IN (:ids) AND p.source_type = 'PRIMARY' AND i.kind IN ('DOCKET', 'RIN')
                ORDER BY d.rule_id, i.kind, i.value
                """, Map.of("ids", ruleIds), rs -> {
            identifiers.computeIfAbsent(rs.getLong("rule_id"), id -> new LinkedHashSet<>())
                    .add(new RuleIdentifier(IdentifierKind.valueOf(rs.getString("kind")), rs.getString("value")));
        });
        return identifiers;
    }

    /** Marks a link for review, adding the reason to any it already has. */
    void flagForReview(long policyId, String reason) {
        jdbc.update("""
                UPDATE policy_rule_documents SET needs_review = TRUE,
                    review_reason = left(CASE WHEN review_reason IS NULL THEN :reason
                                              ELSE review_reason || ' ' || :reason END, 1000)
                WHERE policy_id = :id
                """, Map.of("id", policyId, "reason", reason));
    }

    void link(long policyId, long ruleId, MatchMethod method, Confidence confidence, String matchedOn,
            String reviewReason) {
        jdbc.update("""
                INSERT INTO policy_rule_documents
                    (policy_id, rule_id, match_method, confidence, matched_on, needs_review, review_reason)
                VALUES (:policyId, :ruleId, :method, :confidence, :matchedOn, :needsReview, :reason)
                """, new MapSqlParameterSource()
                .addValue("policyId", policyId)
                .addValue("ruleId", ruleId)
                .addValue("method", method.name())
                .addValue("confidence", confidence.name())
                .addValue("matchedOn", truncate(matchedOn, 500), Types.VARCHAR)
                .addValue("needsReview", reviewReason != null)
                .addValue("reason", truncate(reviewReason, 1000), Types.VARCHAR));
    }

    /**
     * Writes a history row unless the same change from the same source is already there. Returns
     * the new row's id, or empty for a repeat.
     */
    Optional<Long> appendHistory(NewHistoryEntry entry) {
        return appendHistory(entry, null);
    }

    /**
     * Writes the step for a report of litigation held for review because an admin had cleared the
     * rule's dispute: not applied, not alerted, the rule's state the same before and after, and
     * why it was held in reason. V36 requires actor_email and reason together only on an ADMIN
     * step, so this step has a reason and no actor_email.
     */
    void appendHeldReport(long ruleId, RuleState state, RuleSource source, RecordedBy recordedBy, String reason) {
        appendHistory(new NewHistoryEntry(ruleId, state, state, false, false, false, source, recordedBy), reason);
    }

    private Optional<Long> appendHistory(NewHistoryEntry entry, String reason) {
        RuleState previous = entry.previous();
        RuleSource source = entry.source();
        MapSqlParameterSource params = state(entry.state())
                .addValue("ruleId", entry.ruleId())
                .addValue("previousStage", previous == null ? null : previous.primaryStage().name(), Types.VARCHAR)
                .addValue("previousDate", previous == null ? null : previous.effectiveDate(), Types.DATE)
                .addValue("previousDisputed", previous == null ? null : previous.disputed(), Types.BOOLEAN)
                .addValue("applied", entry.applied())
                .addValue("alerted", entry.alerted())
                .addValue("aboutEffectiveDate", entry.aboutEffectiveDate())
                .addValue("sourceId", source.policyId(), Types.BIGINT)
                .addValue("sourceExternalId", source.externalId())
                .addValue("sourceType", source.sourceType().name())
                .addValue("sourceUrl", source.url())
                .addValue("sourceTitle", source.title())
                .addValue("sourceAgency", source.agency())
                .addValue("observedOn", source.publishedOn(), Types.DATE)
                .addValue("recordedBy", entry.recordedBy().name())
                .addValue("reason", truncate(reason, 2000), Types.VARCHAR)
                .addValue("dedupeKey", dedupeKey(entry));
        return jdbc.query("""
                INSERT INTO policy_rule_history
                    (rule_id, primary_stage, effective_date, disputed, previous_primary_stage,
                     previous_effective_date, previous_disputed, applied, alerted, about_effective_date,
                     source_policy_id, source_external_id, source_type, source_url, source_title,
                     source_agency, observed_on, recorded_by, reason, dedupe_key)
                VALUES (:ruleId, :stage, :effectiveDate, :disputed, :previousStage, :previousDate,
                        :previousDisputed, :applied, :alerted, :aboutEffectiveDate, :sourceId,
                        :sourceExternalId, :sourceType, :sourceUrl, :sourceTitle, :sourceAgency, :observedOn,
                        :recordedBy, :reason, :dedupeKey)
                ON CONFLICT (rule_id, dedupe_key) DO NOTHING
                RETURNING id
                """, params, (rs, row) -> rs.getLong(1)).stream().findFirst();
    }

    /** When an admin cleared a dispute: the step's id and when it was written. */
    public record ClearedStep(long historyId, Instant recordedAt) {
    }

    /**
     * Writes the step an admin records clearing the rule's dispute: the state before and after,
     * the source that lifted the block, who cleared it and why. An admin step is never a repeat
     * of another, so its dedupe key is unique.
     */
    ClearedStep appendDisputeCleared(long ruleId, RuleState previous, RuleState state, RuleSource source,
            String actorEmail, String reason) {
        MapSqlParameterSource params = state(state)
                .addValue("ruleId", ruleId)
                .addValue("previousStage", previous.primaryStage().name())
                .addValue("previousDate", previous.effectiveDate(), Types.DATE)
                .addValue("previousDisputed", previous.disputed())
                .addValue("sourceExternalId", source.externalId())
                .addValue("sourceType", source.sourceType().name())
                .addValue("sourceUrl", source.url())
                .addValue("sourceTitle", source.title())
                .addValue("sourceAgency", source.agency())
                .addValue("observedOn", source.publishedOn(), Types.DATE)
                .addValue("recordedBy", RecordedBy.ADMIN.name())
                .addValue("actorEmail", actorEmail)
                .addValue("reason", reason)
                .addValue("dedupeKey", ContentHash.sha256(String.join("|", RecordedBy.ADMIN.name(),
                        "DISPUTE_CLEARED", source.externalId(), UUID.randomUUID().toString())));
        return jdbc.queryForObject("""
                INSERT INTO policy_rule_history
                    (rule_id, primary_stage, effective_date, disputed, previous_primary_stage,
                     previous_effective_date, previous_disputed, applied, alerted, about_effective_date,
                     source_policy_id, source_external_id, source_type, source_url, source_title,
                     source_agency, observed_on, recorded_by, actor_email, reason, dedupe_key)
                VALUES (:ruleId, :stage, :effectiveDate, :disputed, :previousStage, :previousDate,
                        :previousDisputed, TRUE, FALSE, FALSE, NULL, :sourceExternalId, :sourceType,
                        :sourceUrl, :sourceTitle, :sourceAgency, :observedOn, :recordedBy, :actorEmail,
                        :reason, :dedupeKey)
                RETURNING id, recorded_at
                """, params, (rs, row) -> new ClearedStep(rs.getLong("id"), rs.getTimestamp("recorded_at").toInstant()));
    }

    /**
     * The source date of the newest step that set the rule disputed: a secondary report of
     * litigation. Empty when none did.
     */
    Optional<LocalDate> lastDisputedOn(long ruleId) {
        return jdbc.query("""
                SELECT max(observed_on) FROM policy_rule_history
                WHERE rule_id = :id AND source_type = 'SECONDARY' AND recorded_by <> 'ADMIN' AND disputed
                """, Map.of("id", ruleId), (rs, row) -> rs.getObject(1, LocalDate.class))
                .stream().filter(Objects::nonNull).findFirst();
    }

    /** The source date of the newest step in which an admin cleared the rule's dispute. Empty when none did. */
    Optional<LocalDate> lastDisputeClearedOn(long ruleId) {
        return jdbc.query("""
                SELECT max(observed_on) FROM policy_rule_history
                WHERE rule_id = :id AND recorded_by = 'ADMIN' AND NOT disputed
                """, Map.of("id", ruleId), (rs, row) -> rs.getObject(1, LocalDate.class))
                .stream().filter(Objects::nonNull).findFirst();
    }

    /**
     * The rule's primary document behind its current state: the newest published on or before
     * primary_as_of, so a document flagged for moving the stage backwards is never it.
     */
    Optional<Long> currentPrimaryDocumentId(long ruleId) {
        return jdbc.query("""
                SELECT p.id FROM policy_rule_documents d
                JOIN policy_documents p ON p.id = d.policy_id
                JOIN policy_rules r ON r.id = d.rule_id
                WHERE d.rule_id = :id AND p.source_type = 'PRIMARY' AND p.publication_date <= r.primary_as_of
                ORDER BY p.publication_date DESC, p.id DESC LIMIT 1
                """, Map.of("id", ruleId), (rs, row) -> rs.getLong(1)).stream().findFirst();
    }

    /** A change that was alerted, with the title of its rule. */
    public record AlertedChange(String ruleTitle, HistoryRow row) {
    }

    /** The change this document caused and alerted about, if any; a document causes at most one. */
    public Optional<AlertedChange> alertedChangeFrom(long policyId) {
        return jdbc.query("""
                SELECT h.*, r.title AS rule_title FROM policy_rule_history h JOIN policy_rules r ON r.id = h.rule_id
                WHERE h.source_policy_id = :id AND h.alerted ORDER BY h.id LIMIT 1
                """, Map.of("id", policyId), (rs, row) -> new AlertedChange(rs.getString("rule_title"), historyRow(rs)))
                .stream().findFirst();
    }

    /** A document in a rule, with how it was linked. */
    public record RuleDocument(Link link, String externalId, SourceType sourceType, String title, LocalDate publishedOn) {
    }

    /** Every document in the rule, oldest first. */
    public List<RuleDocument> documents(long ruleId) {
        return jdbc.query("""
                SELECT d.*, p.external_id, p.source_type, p.title, p.publication_date
                FROM policy_rule_documents d JOIN policy_documents p ON p.id = d.policy_id
                WHERE d.rule_id = :id ORDER BY p.publication_date, p.id
                """, Map.of("id", ruleId), (rs, row) -> new RuleDocument(link(rs), rs.getString("external_id"),
                        SourceType.valueOf(rs.getString("source_type")), rs.getString("title"),
                        rs.getObject("publication_date", LocalDate.class)));
    }

    /** The rule's history in timeline order: by source publication date, then as recorded. */
    public List<HistoryRow> history(long ruleId) {
        return jdbc.query("""
                SELECT * FROM policy_rule_history WHERE rule_id = :id ORDER BY observed_on, recorded_at, id
                """, Map.of("id", ruleId), (rs, row) -> historyRow(rs));
    }

    /** Documents not in any rule, primary first, each oldest first, so history is written in order. */
    List<Long> unlinkedDocumentIds() {
        return jdbc.query("""
                SELECT p.id FROM policy_documents p
                WHERE NOT EXISTS (SELECT 1 FROM policy_rule_documents d WHERE d.policy_id = p.id)
                ORDER BY CASE p.source_type WHEN 'PRIMARY' THEN 0 ELSE 1 END, p.publication_date, p.id
                """, Map.of(), (rs, row) -> rs.getLong(1));
    }

    /**
     * Identifies one change: the source, the state before and the state after. Including the
     * previous state means a rule that moves back to a value it once had still records the move.
     */
    static String dedupeKey(NewHistoryEntry entry) {
        return ContentHash.sha256(String.join("|", entry.source().externalId(), describe(entry.previous()),
                describe(entry.state()), String.valueOf(entry.applied())));
    }

    private static String describe(RuleState state) {
        return state == null ? "-" : state.primaryStage() + "," + state.effectiveDate() + "," + state.disputed();
    }

    private static MapSqlParameterSource state(RuleState state) {
        return new MapSqlParameterSource()
                .addValue("stage", state.primaryStage().name())
                .addValue("effectiveDate", state.effectiveDate(), Types.DATE)
                .addValue("disputed", state.disputed());
    }

    private static Link link(ResultSet rs) throws SQLException {
        return new Link(rs.getLong("policy_id"), rs.getLong("rule_id"), MatchMethod.valueOf(rs.getString("match_method")),
                Confidence.valueOf(rs.getString("confidence")), rs.getString("matched_on"),
                rs.getBoolean("needs_review"), rs.getString("review_reason"));
    }

    private static HistoryRow historyRow(ResultSet rs) throws SQLException {
        String previousStage = rs.getString("previous_primary_stage");
        RuleState previous = previousStage == null ? null : new RuleState(PolicyStatus.valueOf(previousStage),
                rs.getObject("previous_effective_date", LocalDate.class), rs.getBoolean("previous_disputed"));
        long sourceId = rs.getLong("source_policy_id");
        RuleSource source = new RuleSource(rs.wasNull() ? null : sourceId, rs.getString("source_external_id"),
                SourceType.valueOf(rs.getString("source_type")), rs.getString("source_url"),
                rs.getString("source_title"), rs.getString("source_agency"),
                rs.getObject("observed_on", LocalDate.class));
        return new HistoryRow(rs.getLong("id"), rs.getLong("rule_id"),
                new RuleState(PolicyStatus.valueOf(rs.getString("primary_stage")),
                        rs.getObject("effective_date", LocalDate.class), rs.getBoolean("disputed")),
                previous, rs.getBoolean("applied"), rs.getBoolean("alerted"), rs.getBoolean("about_effective_date"),
                source,
                RecordedBy.valueOf(rs.getString("recorded_by")), rs.getTimestamp("recorded_at").toInstant());
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
