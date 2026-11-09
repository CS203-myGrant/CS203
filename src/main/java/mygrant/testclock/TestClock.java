package mygrant.testclock;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * TEMPORARY testing aid: the date the app treats as "today" when deciding whether a policy
 * has been published yet. An admin can move it for every applicant and an applicant can
 * move it for themselves only; a personal date wins over the admin's. Held in memory, so a
 * restart returns everyone to the real date. Remove with the rest of this package once
 * policy alerts no longer need manual testing.
 */
@Component
public class TestClock {

    private final boolean enabled;
    private volatile LocalDate globalDate;
    private final Map<Long, LocalDate> userDates = new ConcurrentHashMap<>();

    public TestClock(@Value("${mygrant.test-clock.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public LocalDate realToday() {
        return LocalDate.now();
    }

    /** The date this user's alerts are matched against: their own, else the admin's, else today. */
    public LocalDate today(Long userId) {
        if (!enabled) {
            return realToday();
        }
        LocalDate personal = userId == null ? null : userDates.get(userId);
        if (personal != null) {
            return personal;
        }
        LocalDate global = globalDate;
        return global != null ? global : realToday();
    }

    /** True once the simulated date has reached the publication date; an undated policy is always out. */
    public boolean isPublished(LocalDate publicationDate, Long userId) {
        return publicationDate == null || !publicationDate.isAfter(today(userId));
    }

    public LocalDate globalDate() {
        return globalDate;
    }

    public LocalDate userDate(Long userId) {
        return userDates.get(userId);
    }

    /** Moves every applicant to the date, dropping personal dates; null returns everyone to today. */
    public void setGlobalDate(LocalDate date) {
        userDates.clear();
        globalDate = date;
    }

    /** Moves one user to the date; null drops their personal date. */
    public void setUserDate(Long userId, LocalDate date) {
        if (date == null) {
            userDates.remove(userId);
        } else {
            userDates.put(userId, date);
        }
    }
}
