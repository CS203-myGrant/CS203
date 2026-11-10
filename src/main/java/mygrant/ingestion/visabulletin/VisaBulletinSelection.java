package mygrant.ingestion.visabulletin;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import mygrant.user.ProfileFieldDefinition;
import mygrant.user.UserProfile;

/** Maps only explicit, consented profile details to an exact bulletin chart row and column. */
final class VisaBulletinSelection {
    static final String OTHER_AREAS = "ALL CHARGEABILITY AREAS EXCEPT THOSE LISTED";

    private static final Map<String, Category> CATEGORIES = Map.ofEntries(
            Map.entry("F1", new Category("FAMILY", "F1")),
            Map.entry("F2A", new Category("FAMILY", "F2A")),
            Map.entry("F2B", new Category("FAMILY", "F2B")),
            Map.entry("F3", new Category("FAMILY", "F3")),
            Map.entry("F4", new Category("FAMILY", "F4")),
            Map.entry("EB-1", new Category("EMPLOYMENT", "1ST")),
            Map.entry("EB-2", new Category("EMPLOYMENT", "2ND")),
            Map.entry("EB-3", new Category("EMPLOYMENT", "3RD")),
            Map.entry("EB-3 OTHER WORKERS", new Category("EMPLOYMENT", "OTHER WORKERS")),
            Map.entry("EB-4", new Category("EMPLOYMENT", "4TH")),
            Map.entry("EB-4 RELIGIOUS WORKERS", new Category("EMPLOYMENT", "CERTAIN RELIGIOUS WORKERS")),
            Map.entry("EB-5", new Category("EMPLOYMENT", "5TH UNRESERVED")),
            Map.entry("EB-5 RURAL", new Category("EMPLOYMENT", "5TH SET ASIDE: RURAL")),
            Map.entry("EB-5 HIGH UNEMPLOYMENT", new Category("EMPLOYMENT", "5TH SET ASIDE: HIGH UNEMPLOYMENT")),
            Map.entry("EB-5 INFRASTRUCTURE", new Category("EMPLOYMENT", "5TH SET ASIDE: INFRASTRUCTURE")));

    record Category(String preference, String row) {}
    record Selection(String preference, String category, String area, LocalDate priorityDate) {}

    static Selection from(UserProfile profile, Set<ProfileFieldDefinition> consented) {
        if (profile == null || profile.getImmigrantCategory() == null
                || profile.getChargeabilityArea() == null || profile.getPriorityDate() == null) {
            throw new IllegalArgumentException("Save immigrant category, Visa Bulletin chargeability column, and priority date first.");
        }
        if (!consented.containsAll(Set.of(ProfileFieldDefinition.IMMIGRANT_CATEGORY,
                ProfileFieldDefinition.CHARGEABILITY_AREA, ProfileFieldDefinition.PRIORITY_DATE))) {
            throw new IllegalArgumentException("Consent to use immigrant category, chargeability column, and priority date first.");
        }
        String categoryInput = profile.getImmigrantCategory().trim().toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        Category category = CATEGORIES.get(categoryInput);
        if (category == null) {
            throw new IllegalArgumentException("Choose a supported immigrant preference category, such as EB-2 or F2A.");
        }
        String area = profile.getChargeabilityArea().trim().toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        if (!Set.of(OTHER_AREAS, "CHINA-MAINLAND BORN", "INDIA", "MEXICO", "PHILIPPINES")
                .contains(area)) {
            throw new IllegalArgumentException("Choose the exact chargeability column shown in the Visa Bulletin.");
        }
        return new Selection(category.preference(), category.row(), area, profile.getPriorityDate());
    }
}
