package mygrant.demo;

import java.util.List;

import mygrant.impact.ImpactUrgency;

/** Team-judged historical demo cases; these labels are not legal conclusions. */
public final class DemoScenarioCatalog {
    private DemoScenarioCatalog() {}

    public record Scenario(String name, String email, String documentNumber,
            ImpactUrgency expected, String rationale) {}

    public static final List<Scenario> SCENARIOS = List.of(
            new Scenario("Student A: F-1 proposal", "demo.student.a@example.test", "2025-16554",
                    ImpactUrgency.LOW, "F-1 proposal; no imminent travel or deadline"),
            new Scenario("Student B: travel booked", "demo.student.b@example.test", "2025-23570",
                    ImpactUrgency.HIGH, "Iranian F-1 student with travel booked; entry restriction needs prompt review"),
            new Scenario("Student C: F-1 proposal", "demo.student.c@example.test", "2025-16554",
                    ImpactUrgency.MEDIUM, "Potentially relevant F-1 proposal; individual facts require review"),
            new Scenario("Student C: travel restriction", "demo.student.c@example.test", "2025-23570",
                    ImpactUrgency.HIGH, "Somali F-1 student with planned travel; separate policy assessment"),
            new Scenario("Employer: H-1B employee", "demo.employee@example.test", "2025-18601",
                    ImpactUrgency.MEDIUM, "H-1B entry restriction may affect the linked employee"));
}
