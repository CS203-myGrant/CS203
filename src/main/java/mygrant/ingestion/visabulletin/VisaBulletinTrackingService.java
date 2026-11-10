package mygrant.ingestion.visabulletin;

import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import mygrant.forecast.ForecastRiskService;
import mygrant.user.ProfileFieldConsent;
import mygrant.user.ProfileFieldConsentRepository;
import mygrant.user.ProfileFieldDefinition;
import mygrant.user.User;
import mygrant.user.UserProfileRepository;
import mygrant.user.UserRepository;
import mygrant.user.UserRole;

/** Opt-in, in-app cutoff-change alerts, evaluated on import/profile changes rather than reads. */
@Service
public class VisaBulletinTrackingService {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final UserProfileRepository profiles;
    private final ProfileFieldConsentRepository consents;
    private final VisaBulletinStore bulletins;

    public VisaBulletinTrackingService(JdbcTemplate jdbc, UserRepository users,
            UserProfileRepository profiles, ProfileFieldConsentRepository consents,
            VisaBulletinStore bulletins) {
        this.jdbc = jdbc;
        this.users = users;
        this.profiles = profiles;
        this.consents = consents;
        this.bulletins = bulletins;
    }

    @Transactional
    public TrackingView setTracking(String email, boolean enabled) {
        User user = applicant(email);
        long userId = user.getId();
        if (!enabled) {
            clear(userId);
            return viewFor(userId);
        }
        Subscription selected = selectionFor(userId);
        Subscription previous = subscription(userId);
        if (previous != null && !previous.sameDetails(selected)) {
            jdbc.update("DELETE FROM visa_bulletin_alerts WHERE user_id=?", userId);
        }
        jdbc.update("""
                INSERT INTO visa_bulletin_subscriptions
                  (user_id, preference_type, category, chargeability_area, priority_date)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                  preference_type=EXCLUDED.preference_type, category=EXCLUDED.category,
                  chargeability_area=EXCLUDED.chargeability_area,
                  priority_date=EXCLUDED.priority_date
                """, userId, selected.preference(), selected.category(), selected.area(),
                Date.valueOf(selected.priorityDate()));
        return viewFor(userId);
    }

    @Transactional(readOnly = true)
    public TrackingView view(String email) {
        return viewFor(applicant(email).getId());
    }

    /** A withdrawn field or consent stops tracking; changed details clear old-context alerts. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void profileSaved(ForecastRiskService.ProfileSaved event) {
        Subscription previous = subscription(event.userId());
        if (previous == null) return;
        try {
            Subscription current = selectionFor(event.userId());
            if (!previous.sameDetails(current)) {
                jdbc.update("DELETE FROM visa_bulletin_alerts WHERE user_id=?", event.userId());
                jdbc.update("""
                        UPDATE visa_bulletin_subscriptions
                        SET preference_type=?, category=?, chargeability_area=?, priority_date=?
                        WHERE user_id=?
                        """, current.preference(), current.category(), current.area(),
                        Date.valueOf(current.priorityDate()), event.userId());
            }
        } catch (IllegalArgumentException invalid) {
            clear(event.userId());
        }
    }

    /** Only a newly imported latest month can produce change alerts. Re-imports update one row. */
    @EventListener
    @Transactional
    public void imported(VisaBulletinImporter.BulletinImported event) {
        YearMonth month = event.month();
        YearMonth latest = latestMonth();
        if (!month.equals(latest)) return; // Historical backfills never announce old changes.
        Date priorDate = jdbc.queryForObject("""
                SELECT MAX(publication_month) FROM visa_bulletin_imports WHERE publication_month < ?
                """, Date.class, Date.valueOf(month.atDay(1)));
        if (priorDate == null) return; // The first saved month is a baseline.
        YearMonth priorMonth = YearMonth.from(priorDate.toLocalDate());
        Map<Key, VisaBulletinStore.EntryView> current = entries(month);
        Map<Key, VisaBulletinStore.EntryView> previous = entries(priorMonth);
        for (Subscription subscription : subscriptions()) {
            for (String chart : List.of("FINAL_ACTION", "DATES_FOR_FILING")) {
                Key key = new Key(subscription.preference(), chart, subscription.category(), subscription.area());
                var now = current.get(key);
                var before = previous.get(key);
                if (now == null || before == null) continue;
                if (sameCutoff(now, before)) {
                    jdbc.update("""
                            DELETE FROM visa_bulletin_alerts
                            WHERE user_id=? AND publication_month=? AND chart_type=?
                            """, subscription.userId(), Date.valueOf(month.atDay(1)), chart);
                    continue;
                }
                String message = message(subscription, chart, before, now, month);
                jdbc.update("""
                        INSERT INTO visa_bulletin_alerts
                          (user_id, publication_month, previous_month, chart_type, preference_type,
                           category, chargeability_area, previous_cutoff_kind, previous_cutoff_date,
                           cutoff_kind, cutoff_date, priority_date, source_url, message)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (user_id, publication_month, chart_type) DO UPDATE SET
                          previous_month=EXCLUDED.previous_month,
                          previous_cutoff_kind=EXCLUDED.previous_cutoff_kind,
                          previous_cutoff_date=EXCLUDED.previous_cutoff_date,
                          cutoff_kind=EXCLUDED.cutoff_kind, cutoff_date=EXCLUDED.cutoff_date,
                          source_url=EXCLUDED.source_url, message=EXCLUDED.message,
                          updated_at=CURRENT_TIMESTAMP
                        WHERE (visa_bulletin_alerts.previous_month,
                               visa_bulletin_alerts.previous_cutoff_kind,
                               visa_bulletin_alerts.previous_cutoff_date,
                               visa_bulletin_alerts.cutoff_kind, visa_bulletin_alerts.cutoff_date,
                               visa_bulletin_alerts.source_url, visa_bulletin_alerts.message)
                          IS DISTINCT FROM
                              (EXCLUDED.previous_month, EXCLUDED.previous_cutoff_kind,
                               EXCLUDED.previous_cutoff_date, EXCLUDED.cutoff_kind,
                               EXCLUDED.cutoff_date, EXCLUDED.source_url, EXCLUDED.message)
                        """, subscription.userId(), Date.valueOf(month.atDay(1)), priorDate,
                        chart, subscription.preference(), subscription.category(), subscription.area(),
                        before.cutoffKind(), date(before.cutoffDate()), now.cutoffKind(),
                        date(now.cutoffDate()), Date.valueOf(subscription.priorityDate()),
                        now.sourceUrl(), message);
            }
        }
    }

    private TrackingView viewFor(long userId) {
        Subscription subscription = subscription(userId);
        YearMonth month = latestMonth();
        if (subscription == null) {
            return new TrackingView(false, null, null, null, null, month, List.of(), List.of());
        }
        List<CutoffView> cutoffs = month == null ? List.of() : bulletins.entries(month).stream()
                .filter(entry -> entry.preferenceType().equals(subscription.preference())
                        && entry.category().equals(subscription.category())
                        && entry.chargeabilityArea().equals(subscription.area()))
                .map(entry -> new CutoffView(entry.chartType(), entry.cutoffKind(),
                        entry.cutoffDate(), entry.sourceUrl()))
                .toList();
        List<AlertView> alerts = jdbc.query("""
                SELECT id, publication_month, chart_type, previous_cutoff_kind,
                       previous_cutoff_date, cutoff_kind, cutoff_date, source_url, message
                FROM visa_bulletin_alerts WHERE user_id=?
                ORDER BY publication_month DESC, id DESC LIMIT 20
                """, (rs, row) -> new AlertView(rs.getLong("id"),
                YearMonth.from(rs.getDate("publication_month").toLocalDate()),
                rs.getString("chart_type"), rs.getString("previous_cutoff_kind"),
                localDate(rs.getDate("previous_cutoff_date")), rs.getString("cutoff_kind"),
                localDate(rs.getDate("cutoff_date")), rs.getString("source_url"),
                rs.getString("message")), userId);
        return new TrackingView(true, subscription.preference(), subscription.category(),
                subscription.area(), subscription.priorityDate(), month, cutoffs, alerts);
    }

    private Subscription selectionFor(long userId) {
        var profile = profiles.findById(userId).orElseThrow(() ->
                new IllegalArgumentException("Save your profile before enabling Visa Bulletin tracking."));
        Set<ProfileFieldDefinition> allowed = consents.findByUserId(userId).stream()
                .map(ProfileFieldConsent::getFieldName).collect(Collectors.toSet());
        var selection = VisaBulletinSelection.from(profile, allowed);
        return new Subscription(userId, selection.preference(), selection.category(),
                selection.area(), selection.priorityDate());
    }

    private User applicant(String email) {
        User user = users.findByEmail(email).orElseThrow(() ->
                new IllegalArgumentException("Signed-in account was not found."));
        if (!user.isActive() || user.getRole() != UserRole.APPLICANT) {
            throw new IllegalArgumentException("Visa Bulletin tracking is for active applicant accounts.");
        }
        return user;
    }

    private Subscription subscription(long userId) {
        return jdbc.query("""
                SELECT user_id, preference_type, category, chargeability_area, priority_date
                FROM visa_bulletin_subscriptions WHERE user_id=?
                """, rs -> rs.next() ? new Subscription(rs.getLong("user_id"),
                rs.getString("preference_type"), rs.getString("category"),
                rs.getString("chargeability_area"), rs.getDate("priority_date").toLocalDate()) : null,
                userId);
    }

    private List<Subscription> subscriptions() {
        return jdbc.query("""
                SELECT s.user_id, s.preference_type, s.category, s.chargeability_area, s.priority_date
                FROM visa_bulletin_subscriptions s JOIN users u ON u.id=s.user_id
                WHERE u.active=true AND u.role='APPLICANT'
                """, (rs, row) -> new Subscription(rs.getLong("user_id"),
                rs.getString("preference_type"), rs.getString("category"),
                rs.getString("chargeability_area"), rs.getDate("priority_date").toLocalDate()));
    }

    private YearMonth latestMonth() {
        Date value = jdbc.queryForObject("SELECT MAX(publication_month) FROM visa_bulletin_imports", Date.class);
        return value == null ? null : YearMonth.from(value.toLocalDate());
    }

    private Map<Key, VisaBulletinStore.EntryView> entries(YearMonth month) {
        return bulletins.entries(month).stream().collect(Collectors.toMap(
                entry -> new Key(entry.preferenceType(), entry.chartType(), entry.category(),
                        entry.chargeabilityArea()), Function.identity()));
    }

    private boolean sameCutoff(VisaBulletinStore.EntryView a, VisaBulletinStore.EntryView b) {
        return a.cutoffKind().equals(b.cutoffKind()) && Objects.equals(a.cutoffDate(), b.cutoffDate());
    }

    private String message(Subscription selected, String chart, VisaBulletinStore.EntryView before,
            VisaBulletinStore.EntryView now, YearMonth month) {
        return "The " + month + " Visa Bulletin " + chart.replace('_', ' ').toLowerCase()
                + " cutoff " + change(before, now) + " for " + selected.category() + " / " + selected.area()
                + " from " + label(before) + " to " + label(now)
                + ". Your saved priority date is " + selected.priorityDate() + ". "
                + priorityContext(selected.priorityDate(), now)
                + ". Check the official bulletin and USCIS chart selection; this alert does not establish filing eligibility.";
    }

    private String change(VisaBulletinStore.EntryView before, VisaBulletinStore.EntryView now) {
        if (now.cutoffKind().equals("CURRENT")) return "became Current (C)";
        if (now.cutoffKind().equals("UNAVAILABLE")) return "became Unavailable (U)";
        if (!before.cutoffKind().equals("DATE")) return "changed";
        return now.cutoffDate().isAfter(before.cutoffDate()) ? "moved forward" : "moved backward";
    }

    private String priorityContext(LocalDate priorityDate, VisaBulletinStore.EntryView now) {
        if (now.cutoffKind().equals("CURRENT")) return "The published chart says Current (C)";
        if (now.cutoffKind().equals("UNAVAILABLE")) return "The published chart says Unavailable (U)";
        return priorityDate.isBefore(now.cutoffDate())
                ? "Your date is earlier than this published cutoff"
                : "Your date is on or after this published cutoff";
    }

    private String label(VisaBulletinStore.EntryView entry) {
        return switch (entry.cutoffKind()) {
            case "CURRENT" -> "Current (C)";
            case "UNAVAILABLE" -> "Unavailable (U)";
            default -> entry.cutoffDate().toString();
        };
    }

    private void clear(long userId) {
        jdbc.update("DELETE FROM visa_bulletin_alerts WHERE user_id=?", userId);
        jdbc.update("DELETE FROM visa_bulletin_subscriptions WHERE user_id=?", userId);
    }

    private Date date(LocalDate value) { return value == null ? null : Date.valueOf(value); }
    private LocalDate localDate(Date value) { return value == null ? null : value.toLocalDate(); }

    private record Key(String preference, String chart, String category, String area) {}
    private record Subscription(long userId, String preference, String category, String area,
            LocalDate priorityDate) {
        boolean sameDetails(Subscription other) {
            return preference.equals(other.preference) && category.equals(other.category)
                    && area.equals(other.area) && priorityDate.equals(other.priorityDate);
        }
    }

    public record TrackingView(boolean enabled, String preferenceType, String category,
            String chargeabilityArea, LocalDate priorityDate, YearMonth latestMonth,
            List<CutoffView> currentCutoffs, List<AlertView> alerts) {}
    public record CutoffView(String chartType, String cutoffKind, LocalDate cutoffDate, String sourceUrl) {}
    public record AlertView(long id, YearMonth publicationMonth, String chartType,
            String previousCutoffKind, LocalDate previousCutoffDate, String cutoffKind,
            LocalDate cutoffDate, String sourceUrl, String message) {}
}
