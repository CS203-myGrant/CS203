package mygrant.notifications;

import java.util.function.Predicate;

import mygrant.user.UserProfile;

/** Profile information a policy change can require a user to review or update. */
public enum ProfileField {
    PROGRAM_END_DATE("program end date", profile -> profile.getProgramEndDate() != null),
    ACADEMIC_LEVEL("academic level", profile -> isPresent(profile.getAcademicLevel())),
    UPCOMING_TRAVEL("upcoming travel date", profile -> profile.getUpcomingTravelDate() != null),
    // Either half is enough to act on: a worker who names an employer but not a status
    // still depends on that job for their status.
    EMPLOYMENT("employer and employment status",
            profile -> isPresent(profile.getEmployer()) || isPresent(profile.getEmploymentStatus()));

    private final String label;
    private final Predicate<UserProfile> isFilledIn;

    ProfileField(String label, Predicate<UserProfile> isFilledIn) {
        this.label = label;
        this.isFilledIn = isFilledIn;
    }

    public String label() {
        return label;
    }

    public boolean isFilledIn(UserProfile profile) {
        return isFilledIn.test(profile);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
