package mygrant.user;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.constraints.*;

/** One self-reported consular visa application tracker per user. */
@Service
public class ApplicationProgressService {
    private final JdbcTemplate jdbc;
    private final UserRepository users;

    public ApplicationProgressService(JdbcTemplate jdbc, UserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    public record Details(@NotBlank @Size(max = 30) String visaType,
            @NotNull @Size(max = 150) String interviewLocation,
            @NotNull @Size(max = 30) String ds160Confirmation,
            LocalDate interviewDate, LocalDate intendedTravelDate,
            @NotNull @Size(max = 2000) String notes,
            @NotNull @Size(max = 7) List<@NotNull @Min(1) @Max(7) Integer> completedSteps) {}

    public record Progress(Details details, Instant updatedAt) {}

    public Progress get(String email) {
        User user = currentUser(email);
        return read(user.getId()).stream().findFirst().orElseGet(() -> new Progress(
                new Details(user.getVisaType() == null ? "" : user.getVisaType(), "", "", null, null, "", List.of()), null));
    }

    @Transactional
    public Progress save(String email, Details details) {
        Long userId = currentUser(email).getId();
        int mask = details.completedSteps().stream().distinct().mapToInt(step -> 1 << (step - 1)).reduce(0, (a, b) -> a | b);
        jdbc.update("""
                INSERT INTO visa_application_progress
                (user_id, visa_type, interview_location, ds160_confirmation, interview_date, intended_travel_date, notes, completed_steps)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET visa_type = EXCLUDED.visa_type,
                interview_location = EXCLUDED.interview_location, ds160_confirmation = EXCLUDED.ds160_confirmation,
                interview_date = EXCLUDED.interview_date, intended_travel_date = EXCLUDED.intended_travel_date,
                notes = EXCLUDED.notes, completed_steps = EXCLUDED.completed_steps, updated_at = CURRENT_TIMESTAMP
                """, userId, details.visaType().trim(), details.interviewLocation().trim(),
                details.ds160Confirmation().trim(), details.interviewDate(), details.intendedTravelDate(),
                details.notes().trim(), mask);
        return read(userId).getFirst();
    }

    private List<Progress> read(Long userId) {
        return jdbc.query("SELECT * FROM visa_application_progress WHERE user_id = ?", (rs, row) -> {
            int mask = rs.getInt("completed_steps");
            List<Integer> steps = java.util.stream.IntStream.rangeClosed(1, 7)
                    .filter(step -> (mask & (1 << (step - 1))) != 0).boxed().toList();
            return new Progress(new Details(rs.getString("visa_type"), rs.getString("interview_location"),
                    rs.getString("ds160_confirmation"), rs.getObject("interview_date", LocalDate.class),
                    rs.getObject("intended_travel_date", LocalDate.class), rs.getString("notes"), steps),
                    rs.getTimestamp("updated_at").toInstant());
        }, userId);
    }

    private User currentUser(String email) {
        return users.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
    }
}
