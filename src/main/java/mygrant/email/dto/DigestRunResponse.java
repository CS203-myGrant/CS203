package mygrant.email.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

public record DigestRunResponse(
        @Schema(description = "Date the digests were built for, in the digest time zone", example = "2026-10-24")
        LocalDate digestDate,

        @Schema(description = "Digests created by this run; users with nothing new, or a digest for this date already, get none")
        int digestsCreated,

        @Schema(description = "Emails the dispatch after the run sent, including any other emails that were due")
        int emailsSent,

        @Schema(description = "Emails that failed for now and will be retried")
        int emailsRetrying,

        @Schema(description = "Emails that failed for good")
        int emailsFailed,

        @Schema(description = "Emails not sent because the user unsubscribed or can no longer receive email")
        int emailsSkipped
) {
}
