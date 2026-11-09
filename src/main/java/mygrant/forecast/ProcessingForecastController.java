package mygrant.forecast;

import java.security.Principal;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/forecast")
@Tag(name = "USCIS processing forecast",
        description = "Authenticated decision-window projections from saved USCIS processing-time snapshots")
public class ProcessingForecastController {
    private final ProcessingForecastService service;
    private final ForecastRiskService risks;

    public ProcessingForecastController(ProcessingForecastService service, ForecastRiskService risks) {
        this.service = service;
        this.risks = risks;
    }

    @GetMapping("/processing-times")
    @Operation(summary = "Project the signed-in user's USCIS decision window",
            description = "Uses the caller's saved filing date and comparable USCIS snapshots. "
                    + "Optional formCategory and office select an exact series when several exist. "
                    + "The published 80th percentile is not an 80% forecast confidence level. "
                    + "UNAVAILABLE with reason MISSING_FILING_DATE, NO_USABLE_HISTORY, "
                    + "INSUFFICIENT_HISTORY, AMBIGUOUS_SERIES, FILING_DATE_IN_FUTURE, or "
                    + "UNSUPPORTED_FORM has null dates. A median date is null unless USCIS "
                    + "published a median in at least two selected observations.")
    @ApiResponse(responseCode = "200", description = "Projection or explicit unavailable result",
            content = @Content(mediaType = "application/json", examples = {
                    @ExampleObject(name = "unavailable", value = """
                            {"status":"UNAVAILABLE","reason":"MISSING_FILING_DATE",
                             "formType":"I-765","medianDecisionDate":null,
                             "upperBoundDecisionDate":null,"sourceSnapshotDates":[]}
                            """),
                    @ExampleObject(name = "projection", value = """
                            {"status":"AVAILABLE","formType":"I-765","filingDate":"2026-01-01",
                             "rangeStartDate":"2026-03-15","medianDecisionDate":null,
                             "upperBoundDecisionDate":"2026-06-01",
                             "bandType":"UNCALIBRATED_PROJECTION","publishedPercentile":80,
                             "sourceSnapshotDates":["2025-12-01","2025-12-08"]}
                            """) }))
    @ApiResponse(responseCode = "400", description = "Invalid formType in the shared ApiError format")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    public ProcessingForecastService.Forecast get(
            @Parameter(example = "I-765") @RequestParam String formType,
            @RequestParam(required = false) String formCategory,
            @RequestParam(required = false) String office,
            Principal principal) {
        return service.forecast(principal.getName(), formType, formCategory, office);
    }

    @PutMapping("/processing-times/selection")
    @Operation(summary = "Save the signed-in user's USCIS category and office",
            description = "Use an exact category and office offered by the forecast when several series exist. "
                    + "The saved selection is reused for later forecasts and deadline alerts.")
    @ApiResponse(responseCode = "200", description = "The selected comparable series")
    @ApiResponse(responseCode = "400", description = "Invalid or unavailable USCIS series")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    public ProcessingForecastService.Series select(@RequestBody Selection request, Principal principal) {
        return service.selectSeries(principal.getName(), request.formType(),
                request.formCategory(), request.office());
    }

    public record Selection(String formType, String formCategory, String office) {}

    @GetMapping("/deadline-alerts")
    @Operation(summary = "List the signed-in user's active USCIS deadline warnings",
            description = "Returns only the caller's saved timing warnings. Forecast reads never create warnings.")
    @ApiResponse(responseCode = "200", description = "Active timing warnings")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    public List<ForecastRiskService.ForecastAlert> alerts(Principal principal) {
        return risks.alertsFor(principal.getName());
    }
}
