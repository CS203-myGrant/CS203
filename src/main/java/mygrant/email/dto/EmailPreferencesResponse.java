package mygrant.email.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import mygrant.email.DeliveryMode;
import mygrant.email.IneligibleReason;

public record EmailPreferencesResponse(
        @Schema(description = "How alerts that ask you to act are emailed")
        DeliveryMode criticalDelivery,

        @Schema(description = "How all other alerts are emailed")
        DeliveryMode otherDelivery,

        @Schema(description = "Address alert emails go to", example = "student@example.com")
        String email,

        @Schema(description = "False when no alert emails are sent to this account, whatever the modes say")
        boolean canReceiveEmail,

        @Schema(description = "Why the account cannot receive alert emails; null when it can",
                nullable = true)
        IneligibleReason ineligibleReason
) {
}
