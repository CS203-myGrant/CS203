package mygrant.testclock.dto;

import java.time.LocalDate;

/**
 * The caller's test clock and, after a change, what it did to the inboxes it moved.
 *
 * @param today the date the caller's alerts are matched against
 * @param realToday the actual date on the server
 * @param personalDate the caller's own date, or null when they follow the admin's or the real one
 * @param globalDate the admin's date for every applicant, or null when it is the real one
 * @param inboxesSynced how many inboxes the change re-matched
 * @param notificationsAdded alerts created for policies the new date has reached
 * @param notificationsRemoved alerts withdrawn for policies the new date has not reached
 */
public record TestClockResponse(
        LocalDate today,
        LocalDate realToday,
        LocalDate personalDate,
        LocalDate globalDate,
        int inboxesSynced,
        int notificationsAdded,
        int notificationsRemoved
) {
}
