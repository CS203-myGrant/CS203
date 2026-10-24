package mygrant.email;

import java.time.Instant;
import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import mygrant.common.ApiError;
import mygrant.email.dto.DigestRunResponse;

/** Lets an administrator build the daily digests on demand, for demos and testing. */
@RestController
@RequestMapping("/api/v1/admin/email")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Email alerts")
public class EmailAdminController {

    private final DailyDigestService digestService;
    private final EmailDispatcher dispatcher;
    private final EmailSender sender;

    public EmailAdminController(DailyDigestService digestService, EmailDispatcher dispatcher, EmailSender sender) {
        this.digestService = digestService;
        this.dispatcher = dispatcher;
        this.sender = sender;
    }

    @PostMapping("/digest/run")
    @Operation(summary = "Build and send the daily digests now",
            description = "Builds the digests the scheduled job would, then sends every due email. Each user "
                    + "gets at most one digest per date, so a second run for the same date only reaches users "
                    + "who had none yet.")
    @ApiResponse(responseCode = "200", description = "Digests built")
    @ApiResponse(responseCode = "400", description = "The date is malformed or after today in the digest time zone",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "Resend is not configured",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public DigestRunResponse runDigest(
            @Parameter(description = "Digest date (YYYY-MM-DD); defaults to today in the digest time zone")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (!sender.isConfigured()) {
            throw new EmailNotConfiguredException();
        }
        Instant now = Instant.now();
        LocalDate today = digestService.today(now);
        // A future digest would take that date's slot and suppress the real one for its users.
        if (date != null && date.isAfter(today)) {
            throw new IllegalArgumentException("date must not be after today (" + today + ") in the digest time zone");
        }
        LocalDate digestDate = date == null ? today : date;
        int created = digestService.buildDigests(digestDate, now);
        EmailDispatcher.Result sent = dispatcher.dispatchDue(Instant.now());
        return new DigestRunResponse(digestDate, created, sent.sent(), sent.retrying(), sent.failed(), sent.skipped());
    }
}
