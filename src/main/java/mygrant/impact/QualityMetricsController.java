package mygrant.impact;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/admin/metrics")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin")
public class QualityMetricsController {
    private final QualityMetricsService service;
    public QualityMetricsController(QualityMetricsService service) { this.service = service; }

    @GetMapping
    @Operation(summary = "Read AI quality metrics for a UTC date range",
            description = "from/to are inclusive ISO dates; defaults to 30 UTC calendar days ending today. "
                    + "Latest evaluation in range per output type/content version; passRate includes unavailable versions. "
                    + "Null rates/averages mean no denominator. Queue backlog is measured at the exclusive end. "
                    + "Manager override rate excludes unavailable/missing preceding automated verdicts. "
                    + "All rates are fractions in [0,1].")
    @ApiResponse(responseCode = "200", description = "Aggregate metrics with explicit sample sizes and UTC boundaries")
    @ApiResponse(responseCode = "400", description = "Invalid ISO dates or reversed date range")
    public QualityMetricsService.Metrics metrics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.metrics(from, to);
    }
}
