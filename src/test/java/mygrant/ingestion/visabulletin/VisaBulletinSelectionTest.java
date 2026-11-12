package mygrant.ingestion.visabulletin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.Test;

import mygrant.user.ProfileFieldDefinition;
import mygrant.user.UserProfile;

class VisaBulletinSelectionTest {
    private static final Set<ProfileFieldDefinition> CONSENT = Set.of(
            ProfileFieldDefinition.IMMIGRANT_CATEGORY,
            ProfileFieldDefinition.CHARGEABILITY_AREA,
            ProfileFieldDefinition.PRIORITY_DATE);

    @Test
    void mapsImmigrantCategoryToExactChartRowWithoutConfusingStudentF1() {
        UserProfile profile = profile("EB-2", "INDIA");
        var selection = VisaBulletinSelection.from(profile, CONSENT);
        assertThat(selection.preference()).isEqualTo("EMPLOYMENT");
        assertThat(selection.category()).isEqualTo("2ND");
        assertThat(selection.area()).isEqualTo("INDIA");

        profile.setImmigrantCategory("F2A");
        profile.setChargeabilityArea(VisaBulletinSelection.OTHER_AREAS);
        assertThat(VisaBulletinSelection.from(profile, CONSENT).preference()).isEqualTo("FAMILY");
        assertThat(VisaBulletinSelection.from(profile, CONSENT).category()).isEqualTo("F2A");
    }

    @Test
    void rejectsGuessingWhenProfileIsIncompleteOrUnconsented() {
        UserProfile profile = profile("EB-2", "INDIA");
        assertThatThrownBy(() -> VisaBulletinSelection.from(profile, Set.of()))
                .hasMessageContaining("Consent");
        profile.setChargeabilityArea("Singapore");
        assertThatThrownBy(() -> VisaBulletinSelection.from(profile, CONSENT))
                .hasMessageContaining("exact chargeability column");
        profile.setChargeabilityArea("INDIA");
        profile.setImmigrantCategory("F-1");
        assertThatThrownBy(() -> VisaBulletinSelection.from(profile, CONSENT))
                .hasMessageContaining("supported immigrant preference category");
    }

    private UserProfile profile(String category, String area) {
        UserProfile profile = UserProfile.registrationProfile(7L, "F-1", null, null, null, null);
        profile.setImmigrantCategory(category);
        profile.setChargeabilityArea(area);
        profile.setPriorityDate(LocalDate.of(2025, 1, 1));
        return profile;
    }
}
