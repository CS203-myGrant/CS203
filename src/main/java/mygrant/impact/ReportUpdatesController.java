package mygrant.impact;

import java.security.Principal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/** CS203-97: tells applicants when a reviewer has answered one of their recommendation reports. */
@RestController
@RequestMapping("/api/v1/report-updates")
@Tag(name = "Impacts")
public class ReportUpdatesController {
    private final RecommendationReportService reports;

    public ReportUpdatesController(RecommendationReportService reports) {
        this.reports = reports;
    }

    @GetMapping
    @Operation(summary = "List reviewer outcomes on your reports",
            description = "Up to 20, newest first, with the reviewer's note and how many you have not seen.")
    @ApiResponse(responseCode = "200", description = "Your report outcomes and the unseen count")
    public RecommendationReportService.Updates updates(Principal principal) {
        return reports.updates(principal.getName());
    }

    @PostMapping("/seen")
    @Operation(summary = "Mark report outcomes as seen",
            description = "All of them, or with `assessmentId` only those on that assessment.")
    @ApiResponse(responseCode = "200", description = "How many outcomes were newly marked as seen")
    public Seen markSeen(Principal principal, @RequestBody(required = false) SeenRequest request) {
        return new Seen(reports.markSeen(principal.getName(), request == null ? null : request.assessmentId()));
    }

    public record SeenRequest(@Schema(description = "Only this assessment's outcomes; omit for all") Long assessmentId) {}
    public record Seen(int marked) {}
}
